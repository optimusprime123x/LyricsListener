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
    fun get(artist: String?, title: String?, durationMs: Long): LyricsData? = lock.read {
        try {
            val normalizedArtist = normalizeString(artist ?: "")
            val normalizedTitle = normalizeString(title ?: "")

            if (normalizedTitle.isEmpty()) {
                Log.d(TAG, "Cannot retrieve from cache: title is empty")
                return null
            }

            Log.d(TAG, "Cache lookup for: '$normalizedArtist' - '$normalizedTitle' (original: '${artist ?: ""}' - '${title ?: ""}')")

            // Find matching cache file with duration tolerance and fuzzy artist matching
            val matchingFile = findMatchingCacheFile(normalizedArtist, normalizedTitle, durationMs)
            if (matchingFile == null) {
                Log.d(TAG, "Cache miss for: '$normalizedArtist' - '$normalizedTitle'. Will fallback to network fetch.")
                return null
            }

            val json = JSONObject(matchingFile.readText())
            val cachedData = parseLyricsFromJson(json)

            if (cachedData == null) {
                Log.w(TAG, "Cache file found but parsing failed for: '$normalizedArtist' - '$normalizedTitle'. Will fallback to network fetch.")
                return null
            }

            // Update last accessed time (best effort, don't fail if this errors)
            try {
                updateAccessTime(matchingFile.nameWithoutExtension)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update access time, continuing anyway", e)
            }

            Log.d(TAG, "Cache hit successfully loaded for: '$normalizedArtist' - '$normalizedTitle'")
            return cachedData
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
                    if (file.delete()) count++
                }
            }
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
            return files.filter { it.isFile && it.name.endsWith(".json") }
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
            return files.count { it.isFile && it.name.endsWith(".json") && it.name != "metadata.json" }
        } catch (e: Exception) {
            return 0
        }
    }

    // Private helper methods

    private fun findMatchingCacheFile(artist: String, title: String, durationMs: Long): File? {
        val files = cacheDir.listFiles() ?: return null

        return files.firstOrNull { file ->
            if (!file.isFile || !file.name.endsWith(".json") || file.name == "metadata.json") {
                return@firstOrNull false
            }

            try {
                val json = JSONObject(file.readText())
                val cachedArtist = normalizeString(json.optString("artist", ""))
                val cachedTitle = normalizeString(json.optString("title", ""))
                val cachedDuration = json.optLong("durationMs", 0L)

                val artistMatch = fuzzyArtistMatch(cachedArtist, artist)
                val titleMatch = cachedTitle == title
                val durationMatch = Math.abs(cachedDuration - durationMs) <= DURATION_TOLERANCE_MS

                val matches = artistMatch && titleMatch && durationMatch

                if (matches) {
                    Log.d(TAG, "Cache match found: '$cachedArtist' - '$cachedTitle' (normalized) matches query '$artist' - '$title'")
                }

                matches
            } catch (e: Exception) {
                Log.e(TAG, "Error reading cache file ${file.name}, skipping", e)
                false
            }
        }
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
            if (!cacheFile.exists()) return

            val json = JSONObject(cacheFile.readText())
            json.put("lastAccessed", System.currentTimeMillis())
            cacheFile.writeText(json.toString())
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
            it.isFile && it.name.endsWith(".json") && it.name != "metadata.json"
        } ?: return

        val filesWithAccessTime = files.map { file ->
            Pair(file, file.lastModified())
        }.sortedBy { it.second } // Sort by access time (oldest first)

        // Delete oldest files until we're under the limit
        var totalSize = currentSize
        for ((file, _) in filesWithAccessTime) {
            if (totalSize <= MAX_CACHE_SIZE_BYTES) break

            val fileSize = file.length()
            if (file.delete()) {
                totalSize -= fileSize
                Log.d(TAG, "Evicted: ${file.name}")
            }
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
