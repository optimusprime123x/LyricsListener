package dev.optimus.lyricslistener

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Manages disk-based caching of lyrics data.
 * - Caches Musixmatch and LRCLib lyrics
 * - Max cache size: 100MB
 * - LRU eviction when limit reached
 * - Duration tolerance: ±3 seconds for matching
 */
class LyricsCacheManager(context: Context) {
    private val cacheDir: File = File(context.cacheDir, "lyrics")
    private val metadataFile: File = File(cacheDir, "metadata.json")
    private val lock = ReentrantReadWriteLock()

    companion object {
        private const val TAG = "LyricsCacheManager"
        private const val MAX_CACHE_SIZE_BYTES = 100 * 1024 * 1024L // 100MB
        private const val DURATION_TOLERANCE_MS = 3000L // ±3 seconds
    }

    private data class MetadataEntry(
        val cacheKey: String,
        val artist: String,
        val title: String,
        val durationMs: Long
    )

    private data class MetadataIndex(
        val entries: MutableList<MetadataEntry>
    )

    private data class CacheFileLookupResult(
        val file: File?,
        val staleKeys: Set<String>
    )

    private data class CacheLookupResult(
        val data: LyricsData?,
        val staleKeys: Set<String>
    )

    private data class MetadataStalenessCheck(
        val shouldRebuild: Boolean,
        val reason: String,
        val cacheFileCount: Int,
        val metadataEntryCount: Int,
        val missingInMetadataCount: Int,
        val missingInCacheCount: Int
    )

    private var metadataCache: MetadataIndex? = null
    private var metadataCacheLastModified: Long = -1L

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
            Log.d(TAG, "Cache directory created: ${cacheDir.absolutePath}")
        }
    }

    /**
     * Retrieves cached lyrics for the given song.
     * @return LyricsData if found in cache, null otherwise
     * Always returns null on any error to ensure fallback to network fetch
     */
    fun get(artist: String?, title: String?, durationMs: Long): LyricsData? {
        try {
            val normalizedArtist = normalizeString(artist ?: "")
            val normalizedTitle = normalizeString(title ?: "")

            if (normalizedTitle.isEmpty()) {
                Log.d(TAG, "Cannot retrieve from cache: title is empty")
                return null
            }

            Log.d(TAG, "Cache lookup for: '$normalizedArtist' - '$normalizedTitle' (original: '${artist ?: ""}' - '${title ?: ""}')")

            ensureMetadataLoaded()

            var lookupResult = lock.read {
                val index = metadataCache ?: MetadataIndex(mutableListOf())
                val fileLookup = findMatchingCacheFile(index, normalizedArtist, normalizedTitle, durationMs)
                val matchingFile = fileLookup.file

                if (matchingFile == null) {
                    Log.d(TAG, "Cache miss in metadata index for: '$normalizedArtist' - '$normalizedTitle'")
                    return@read CacheLookupResult(null, fileLookup.staleKeys)
                }

                val json = JSONObject(matchingFile.readText())
                val cachedData = parseLyricsFromJson(json)

                if (cachedData == null) {
                    Log.w(TAG, "Cache file found but parsing failed for: '$normalizedArtist' - '$normalizedTitle'. Will fallback to network fetch.")
                    val staleKeys = fileLookup.staleKeys.toMutableSet()
                    staleKeys.add(matchingFile.nameWithoutExtension)
                    return@read CacheLookupResult(null, staleKeys)
                }

                // Update last accessed time (best effort, don't fail if this errors)
                try {
                    updateAccessTime(matchingFile.nameWithoutExtension)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update access time, continuing anyway", e)
                }

                Log.d(TAG, "Cache hit successfully loaded for: '$normalizedArtist' - '$normalizedTitle'")
                return@read CacheLookupResult(cachedData, fileLookup.staleKeys)
            }

            var rebuiltMetadata = false
            if (lookupResult.data == null) {
                val staleness = lock.read { checkMetadataStalenessOnMissLocked() }
                if (staleness.shouldRebuild) {
                    Log.d(
                        TAG,
                        "Metadata index appears stale after cache miss (reason=${staleness.reason}, " +
                            "cacheFiles=${staleness.cacheFileCount}, metadataEntries=${staleness.metadataEntryCount}, " +
                            "missingInMetadata=${staleness.missingInMetadataCount}, " +
                            "missingInCache=${staleness.missingInCacheCount}); rebuilding and retrying lookup"
                    )
                    lock.write {
                        val rebuilt = rebuildMetadataIndexLocked()
                        metadataCache = rebuilt
                        metadataCacheLastModified = metadataFile.lastModified()
                    }
                    rebuiltMetadata = true

                    lookupResult = lock.read {
                        val index = metadataCache ?: MetadataIndex(mutableListOf())
                        val fileLookup = findMatchingCacheFile(index, normalizedArtist, normalizedTitle, durationMs)
                        val matchingFile = fileLookup.file

                        if (matchingFile == null) {
                            return@read CacheLookupResult(null, fileLookup.staleKeys)
                        }

                        val json = JSONObject(matchingFile.readText())
                        val cachedData = parseLyricsFromJson(json)

                        if (cachedData == null) {
                            val staleKeys = fileLookup.staleKeys.toMutableSet()
                            staleKeys.add(matchingFile.nameWithoutExtension)
                            return@read CacheLookupResult(null, staleKeys)
                        }

                        try {
                            updateAccessTime(matchingFile.nameWithoutExtension)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update access time, continuing anyway", e)
                        }

                        Log.d(TAG, "Cache hit successfully loaded for: '$normalizedArtist' - '$normalizedTitle'")
                        return@read CacheLookupResult(cachedData, fileLookup.staleKeys)
                    }
                } else {
                    Log.d(TAG, "Metadata index looks current after cache miss; skipping rebuild")
                }
            }

            if (lookupResult.staleKeys.isNotEmpty()) {
                lock.write {
                    pruneMetadataEntriesLocked(lookupResult.staleKeys)
                }
            }

            if (lookupResult.data == null) {
                Log.d(TAG, "Cache miss for: '$normalizedArtist' - '$normalizedTitle'. Will fallback to network fetch.")
            } else if (rebuiltMetadata) {
                Log.d(TAG, "Cache hit after metadata rebuild for: '$normalizedArtist' - '$normalizedTitle'")
            }

            return lookupResult.data
        } catch (e: Exception) {
            Log.e(TAG, "Error reading from cache for '${artist ?: ""}' - '${title ?: ""}'. Will fallback to network fetch.", e)
            return null
        }
    }

    /**
     * Saves lyrics to cache (Musixmatch and LRCLib).
     * @param source Must be "musixmatch" or "lrclib" - other sources are ignored
     */
    fun put(artist: String?, title: String?, durationMs: Long, lyrics: LyricsData, source: String) = lock.write {
        try {
            val lowercaseSource = source.lowercase()
            if (lowercaseSource != "musixmatch" && lowercaseSource != "lrclib") {
                Log.d(TAG, "Skipping cache for unsupported source: $source")
                return@write
            }

            val normalizedArtist = normalizeString(artist ?: "")
            val normalizedTitle = normalizeString(title ?: "")

            if (normalizedTitle.isEmpty()) {
                Log.d(TAG, "Cannot cache: title is empty")
                return@write
            }

            val cacheKey = generateCacheKey(normalizedArtist, normalizedTitle, durationMs)
            val cacheFile = File(cacheDir, "$cacheKey.json")

            val json = JSONObject().apply {
                put("type", when (lyrics) {
                    is LyricsData.Synced -> "synced"
                    is LyricsData.Plain -> "plain"
                    else -> return@write // Don't cache Info or MismatchInfo
                })
                put("title", title ?: "")
                put("artist", artist ?: "")
                put("durationMs", durationMs)
                put("source", source)
                put("cachedAt", System.currentTimeMillis())
                put("lastAccessed", System.currentTimeMillis())

                when (lyrics) {
                    is LyricsData.Synced -> {
                        put("lines", JSONArray(lyrics.lines.map { line ->
                            JSONObject().apply {
                                put("timestamp", line.timestamp)
                                put("text", line.text)
                            }
                        }))
                        lyrics.translatedLines?.let { translated ->
                            put("translatedLines", JSONArray(translated.map { line ->
                                JSONObject().apply {
                                    put("timestamp", line.timestamp)
                                    put("text", line.text)
                                }
                            }))
                        }
                    }
                    is LyricsData.Plain -> {
                        put("lyrics", lyrics.lyrics)
                    }
                    else -> {}
                }
            }

            cacheFile.writeText(json.toString())
            Log.d(TAG, "Cached lyrics for: $normalizedArtist - $normalizedTitle")

            upsertMetadataEntryLocked(
                cacheKey = cacheKey,
                artist = normalizedArtist,
                title = normalizedTitle,
                durationMs = durationMs
            )

            // Check cache size and evict if needed
            ensureCacheSizeLimit()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to cache", e)
        }
    }

    /**
     * Clears all cached lyrics.
     * @return Number of files deleted
     */
    fun clearAll(): Int = lock.write {
        try {
            val files = cacheDir.listFiles() ?: return 0
            var count = 0
            files.forEach { file ->
                if (file.isFile && file.name.endsWith(".json")) {
                    val isMetadata = file.name == metadataFile.name
                    if (file.delete() && !isMetadata) count++
                }
            }
            val emptyIndex = MetadataIndex(mutableListOf())
            metadataCache = emptyIndex
            writeMetadataIndexLocked(emptyIndex)
            Log.d(TAG, "Cleared cache: $count files deleted")
            return count
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
            return 0
        }
    }

    /**
     * Gets current cache size in bytes.
     */
    fun getCacheSizeBytes(): Long = lock.read {
        try {
            val files = cacheDir.listFiles() ?: return 0L
            return files.filter { it.isFile && it.name.endsWith(".json") && it.name != metadataFile.name }
                .sumOf { it.length() }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating cache size", e)
            return 0L
        }
    }

    /**
     * Gets number of cached items.
     */
    fun getCacheCount(): Int = lock.read {
        try {
            val files = cacheDir.listFiles() ?: return 0
            return files.count { it.isFile && it.name.endsWith(".json") && it.name != metadataFile.name }
        } catch (e: Exception) {
            return 0
        }
    }

    // Private helper methods

    private fun ensureMetadataLoaded() {
        val currentLastModified = metadataFile.lastModified()
        val needsReload = lock.read {
            metadataCache == null || metadataCacheLastModified != currentLastModified
        }

        if (!needsReload) return

        lock.write {
            val fileLastModified = metadataFile.lastModified()
            if (metadataCache != null && metadataCacheLastModified == fileLastModified) {
                return@write
            }

            val fromFile = readMetadataFromFile()
            if (fromFile != null) {
                metadataCache = fromFile
                metadataCacheLastModified = fileLastModified
                return@write
            }

            val rebuilt = rebuildMetadataIndexLocked()
            metadataCache = rebuilt
            metadataCacheLastModified = metadataFile.lastModified()
        }
    }

    private fun loadMetadataIndexLocked(): MetadataIndex {
        val fileLastModified = metadataFile.lastModified()
        val cached = metadataCache
        if (cached != null && metadataCacheLastModified == fileLastModified) {
            return cached
        }

        val fromFile = readMetadataFromFile()
        if (fromFile != null) {
            metadataCache = fromFile
            metadataCacheLastModified = fileLastModified
            return fromFile
        }

        val rebuilt = rebuildMetadataIndexLocked()
        metadataCache = rebuilt
        metadataCacheLastModified = metadataFile.lastModified()
        return rebuilt
    }

    private fun checkMetadataStalenessOnMissLocked(): MetadataStalenessCheck {
        val files = cacheDir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".json") && it.name != metadataFile.name
        } ?: return MetadataStalenessCheck(
            shouldRebuild = false,
            reason = "cache_list_failed",
            cacheFileCount = 0,
            metadataEntryCount = metadataCache?.entries?.size ?: 0,
            missingInMetadataCount = 0,
            missingInCacheCount = 0
        )

        if (files.isEmpty()) {
            return MetadataStalenessCheck(
                shouldRebuild = false,
                reason = "no_cache_files",
                cacheFileCount = 0,
                metadataEntryCount = metadataCache?.entries?.size ?: 0,
                missingInMetadataCount = 0,
                missingInCacheCount = 0
            )
        }

        val index = metadataCache ?: MetadataIndex(mutableListOf())
        val cacheFileCount = files.size
        val metadataEntryCount = index.entries.size

        val cacheKeys = HashSet<String>(cacheFileCount)
        files.forEach { file ->
            cacheKeys.add(file.nameWithoutExtension)
        }

        val metadataKeys = HashSet<String>(metadataEntryCount)
        index.entries.forEach { entry ->
            metadataKeys.add(entry.cacheKey)
        }

        var missingInMetadataCount = 0
        for (key in cacheKeys) {
            if (!metadataKeys.contains(key)) {
                missingInMetadataCount++
            }
        }

        var missingInCacheCount = 0
        for (key in metadataKeys) {
            if (!cacheKeys.contains(key)) {
                missingInCacheCount++
            }
        }

        val shouldRebuild = missingInMetadataCount > 0 || missingInCacheCount > 0
        val reason = when {
            missingInMetadataCount > 0 && missingInCacheCount > 0 -> "cache_key_set_mismatch"
            missingInMetadataCount > 0 -> "cache_key_missing_in_metadata"
            missingInCacheCount > 0 -> "metadata_key_missing_in_cache"
            else -> "metadata_current"
        }

        return MetadataStalenessCheck(
            shouldRebuild = shouldRebuild,
            reason = reason,
            cacheFileCount = cacheFileCount,
            metadataEntryCount = metadataEntryCount,
            missingInMetadataCount = missingInMetadataCount,
            missingInCacheCount = missingInCacheCount
        )
    }

    private fun readMetadataFromFile(): MetadataIndex? {
        if (!metadataFile.exists()) return null

        return try {
            val json = JSONObject(metadataFile.readText())
            val version = json.optInt("version", 1)
            if (version != 1) {
                Log.w(TAG, "Unknown metadata version $version, rebuilding index")
                return null
            }

            val entriesArray = json.optJSONArray("entries") ?: JSONArray()
            val entries = mutableListOf<MetadataEntry>()
            for (i in 0 until entriesArray.length()) {
                val entryJson = entriesArray.optJSONObject(i) ?: continue
                val cacheKey = entryJson.optString("cacheKey", "")
                val artist = entryJson.optString("artist", "")
                val title = entryJson.optString("title", "")
                val durationMs = entryJson.optLong("durationMs", Long.MIN_VALUE)

                if (cacheKey.isBlank() || title.isBlank() || durationMs == Long.MIN_VALUE) {
                    continue
                }

                entries.add(
                    MetadataEntry(
                        cacheKey = cacheKey,
                        artist = artist,
                        title = title,
                        durationMs = durationMs
                    )
                )
            }
            MetadataIndex(entries)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read metadata index, rebuilding", e)
            null
        }
    }

    private fun rebuildMetadataIndexLocked(): MetadataIndex {
        val entries = mutableListOf<MetadataEntry>()
        val files = cacheDir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".json") && it.name != metadataFile.name
        } ?: emptyList()

        Log.d(TAG, "Rebuilding metadata index from ${files.size} cache files")

        files.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                val cachedArtist = normalizeString(json.optString("artist", ""))
                val cachedTitle = normalizeString(json.optString("title", ""))
                val cachedDuration = json.optLong("durationMs", Long.MIN_VALUE)

                if (cachedTitle.isEmpty() || cachedDuration == Long.MIN_VALUE) {
                    return@forEach
                }

                entries.add(
                    MetadataEntry(
                        cacheKey = file.nameWithoutExtension,
                        artist = cachedArtist,
                        title = cachedTitle,
                        durationMs = cachedDuration
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error reading cache file ${file.name} while rebuilding metadata, skipping", e)
            }
        }

        val index = MetadataIndex(entries)
        writeMetadataIndexLocked(index)
        return index
    }

    private fun writeMetadataIndexLocked(index: MetadataIndex) {
        try {
            val entriesArray = JSONArray()
            index.entries.forEach { entry ->
                val entryJson = JSONObject().apply {
                    put("cacheKey", entry.cacheKey)
                    put("artist", entry.artist)
                    put("title", entry.title)
                    put("durationMs", entry.durationMs)
                }
                entriesArray.put(entryJson)
            }

            val json = JSONObject().apply {
                put("version", 1)
                put("entries", entriesArray)
            }

            val tempFile = File(cacheDir, "metadata.json.tmp")
            tempFile.writeText(json.toString())

            if (metadataFile.exists() && !metadataFile.delete()) {
                Log.w(TAG, "Failed to delete old metadata file before rewrite")
            }

            if (!tempFile.renameTo(metadataFile)) {
                Log.w(TAG, "Atomic rename failed for metadata, falling back to direct write")
                metadataFile.writeText(json.toString())
                tempFile.delete()
            }

            metadataCacheLastModified = metadataFile.lastModified()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing metadata index", e)
        }
    }

    private fun upsertMetadataEntryLocked(
        cacheKey: String,
        artist: String,
        title: String,
        durationMs: Long
    ) {
        val index = loadMetadataIndexLocked()
        val existingIndex = index.entries.indexOfFirst { it.cacheKey == cacheKey }
        if (existingIndex >= 0) {
            index.entries.removeAt(existingIndex)
        }
        index.entries.add(
            MetadataEntry(
                cacheKey = cacheKey,
                artist = artist,
                title = title,
                durationMs = durationMs
            )
        )
        writeMetadataIndexLocked(index)
    }

    private fun pruneMetadataEntriesLocked(staleKeys: Set<String>) {
        if (staleKeys.isEmpty()) return

        val index = loadMetadataIndexLocked()
        val originalSize = index.entries.size
        index.entries.removeAll { staleKeys.contains(it.cacheKey) }

        if (index.entries.size != originalSize) {
            writeMetadataIndexLocked(index)
        }
    }

    private fun findMatchingCacheFile(
        index: MetadataIndex,
        artist: String,
        title: String,
        durationMs: Long
    ): CacheFileLookupResult {
        if (index.entries.isEmpty()) {
            return CacheFileLookupResult(null, emptySet())
        }

        val staleKeys = mutableSetOf<String>()

        for (entry in index.entries) {
            val artistMatch = fuzzyArtistMatch(entry.artist, artist)
            val titleMatch = entry.title == title
            val durationMatch = Math.abs(entry.durationMs - durationMs) <= DURATION_TOLERANCE_MS

            if (artistMatch && titleMatch && durationMatch) {
                val file = File(cacheDir, "${entry.cacheKey}.json")
                if (file.exists()) {
                    Log.d(TAG, "Cache match found via metadata: '${entry.artist}' - '${entry.title}' (normalized) matches query '$artist' - '$title'")
                    return CacheFileLookupResult(file, staleKeys)
                }

                staleKeys.add(entry.cacheKey)
            }
        }

        return CacheFileLookupResult(null, staleKeys)
    }

    /**
     * Checks if two artist strings match, allowing for variations like:
     * - "The Chainsmoker, Ship Wrek" vs "The Chainsmokers"
     * - "Artist (feat. Other)" vs "Artist"
     * - Minor spelling differences
     */
    private fun fuzzyArtistMatch(artist1: String, artist2: String): Boolean {
        // Exact match
        if (artist1 == artist2) return true

        // Empty check
        if (artist1.isEmpty() || artist2.isEmpty()) return false

        // Extract primary artist (before comma, feat, &, etc.)
        val primary1 = extractPrimaryArtist(artist1)
        val primary2 = extractPrimaryArtist(artist2)

        // Check if primary artists match
        if (primary1 == primary2) return true

        // Check if one contains the other (for "chainsmoker" vs "chainsmokers")
        if (primary1.contains(primary2) || primary2.contains(primary1)) return true

        // Calculate word overlap percentage
        val words1 = primary1.split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        val words2 = primary2.split(Regex("\\s+")).filter { it.length > 2 }.toSet()

        if (words1.isEmpty() || words2.isEmpty()) return false

        val overlap = words1.intersect(words2).size
        val minSize = minOf(words1.size, words2.size)
        val overlapPercentage = overlap.toDouble() / minSize

        // Match if at least 70% of words overlap
        return overlapPercentage >= 0.7
    }

    /**
     * Extracts the primary artist name before collaborators/features.
     * "artist feat other" -> "artist"
     * "artist, other" -> "artist"
     * "artist and other" -> "artist"
     */
    private fun extractPrimaryArtist(artist: String): String {
        return artist
            .split(Regex(",|feat|ft|featuring|&|\\||x(?=\\s)|\\band\\b|\\bwith\\b|\\bvs\\.?\\b", RegexOption.IGNORE_CASE))
            .firstOrNull()
            ?.trim()
            ?: artist
    }

    private fun parseLyricsFromJson(json: JSONObject): LyricsData? {
        return try {
            val type = json.getString("type")
            val title = json.getString("title")
            val artist = json.optString("artist", null)
            val durationMs = json.getLong("durationMs")

            when (type) {
                "synced" -> {
                    val linesArray = json.getJSONArray("lines")
                    val lines = (0 until linesArray.length()).map { i ->
                        val lineObj = linesArray.getJSONObject(i)
                        TimedLyricLine(
                            timestamp = lineObj.getLong("timestamp"),
                            text = lineObj.getString("text")
                        )
                    }

                    val translatedLines = if (json.has("translatedLines")) {
                        val translatedArray = json.getJSONArray("translatedLines")
                        (0 until translatedArray.length()).map { i ->
                            val lineObj = translatedArray.getJSONObject(i)
                            TimedLyricLine(
                                timestamp = lineObj.getLong("timestamp"),
                                text = lineObj.getString("text")
                            )
                        }
                    } else null

                    LyricsData.Synced(
                        title = title,
                        artist = artist,
                        lines = lines,
                        translatedLines = translatedLines,
                        durationMs = durationMs
                    )
                }
                "plain" -> {
                    val lyrics = json.getString("lyrics")
                    LyricsData.Plain(
                        title = title,
                        artist = artist,
                        lyrics = lyrics,
                        durationMs = durationMs
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing cached lyrics", e)
            null
        }
    }

    private fun updateAccessTime(cacheKey: String) {
        try {
            val cacheFile = File(cacheDir, "$cacheKey.json")
            if (cacheFile.exists()) {
                cacheFile.setLastModified(System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating access time", e)
        }
    }

    private fun ensureCacheSizeLimit() {
        val currentSize = getCacheSizeBytes()
        if (currentSize <= MAX_CACHE_SIZE_BYTES) {
            return
        }

        Log.d(TAG, "Cache size ($currentSize bytes) exceeds limit, evicting oldest entries")

        // Get all cache files with their last access time
        val files = cacheDir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".json") && it.name != metadataFile.name
        } ?: return

        // Note: We intentionally rely on File.lastModified() here. For problematic or
        // unreadable files it will typically return 0L, which makes them appear as the
        // "oldest" entries and therefore they are evicted first. This replaces the older
        // implementation that used mapNotNull with explicit exception handling.
        val filesWithAccessTime = files.map { file ->
            Pair(file, file.lastModified())
        }.sortedBy { it.second } // Sort by access time (oldest first)

        // Delete oldest files until we're under the limit
        var totalSize = currentSize
        val evictedKeys = mutableSetOf<String>()
        for ((file, _) in filesWithAccessTime) {
            if (totalSize <= MAX_CACHE_SIZE_BYTES) break

            val fileSize = file.length()
            if (file.delete()) {
                totalSize -= fileSize
                evictedKeys.add(file.nameWithoutExtension)
                Log.d(TAG, "Evicted: ${file.name}")
            }
        }

        if (evictedKeys.isNotEmpty()) {
            pruneMetadataEntriesLocked(evictedKeys)
        }

        Log.d(TAG, "Cache size after eviction: $totalSize bytes")
    }

    private fun normalizeString(str: String): String {
        return str.trim()
            .lowercase()
            // Normalize common artist separators to spaces before removing special chars
            .replace(Regex("\\s+and\\s+"), " ") // " and " -> " "
            .replace(Regex("\\s+with\\s+"), " ") // " with " -> " "
            .replace(Regex("\\s+x\\s+"), " ") // " x " -> " "
            .replace(Regex("\\s+vs\\.?\\s+"), " ") // " vs " or " vs. " -> " "
            .replace(Regex("\\s*&\\s*"), " ") // " & " -> " "
            .replace(Regex("\\s*,\\s*"), " ") // " , " or "," -> " "
            .replace(Regex("[^a-z0-9\\s]"), "") // Remove remaining special chars
            .replace(Regex("\\s+"), " ") // Normalize whitespace
            .trim()
    }

    private fun generateCacheKey(artist: String, title: String, durationMs: Long): String {
        val combined = "${artist}_${title}_${durationMs}"
        return combined.toMD5()
    }

    private fun String.toMD5(): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(this.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
