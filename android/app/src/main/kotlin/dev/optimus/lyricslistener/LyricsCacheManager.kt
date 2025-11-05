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
 * - Only caches Musixmatch lyrics (never LRCLib)
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
     */
    fun get(artist: String?, title: String?, durationMs: Long): LyricsData? = lock.read {
        try {
            val normalizedArtist = normalizeString(artist ?: "")
            val normalizedTitle = normalizeString(title ?: "")

            if (normalizedTitle.isEmpty()) {
                Log.d(TAG, "Cannot retrieve from cache: title is empty")
                return null
            }

            // Find matching cache file with duration tolerance
            val matchingFile = findMatchingCacheFile(normalizedArtist, normalizedTitle, durationMs)
            if (matchingFile == null) {
                Log.d(TAG, "Cache miss for: $normalizedArtist - $normalizedTitle")
                return null
            }

            val json = JSONObject(matchingFile.readText())
            val cachedData = parseLyricsFromJson(json)

            // Update last accessed time
            updateAccessTime(matchingFile.nameWithoutExtension)

            Log.d(TAG, "Cache hit for: $normalizedArtist - $normalizedTitle")
            return cachedData
        } catch (e: Exception) {
            Log.e(TAG, "Error reading from cache", e)
            return null
        }
    }

    /**
     * Saves lyrics to cache (only Musixmatch).
     * @param source Must be "musixmatch" - other sources are ignored
     */
    fun put(artist: String?, title: String?, durationMs: Long, lyrics: LyricsData, source: String) = lock.write {
        try {
            if (source.lowercase() != "musixmatch") {
                Log.d(TAG, "Skipping cache for non-Musixmatch source: $source")
                return
            }

            val normalizedArtist = normalizeString(artist ?: "")
            val normalizedTitle = normalizeString(title ?: "")

            if (normalizedTitle.isEmpty()) {
                Log.d(TAG, "Cannot cache: title is empty")
                return
            }

            val cacheKey = generateCacheKey(normalizedArtist, normalizedTitle, durationMs)
            val cacheFile = File(cacheDir, "$cacheKey.json")

            val json = JSONObject().apply {
                put("type", when (lyrics) {
                    is LyricsData.Synced -> "synced"
                    is LyricsData.Plain -> "plain"
                    else -> return // Don't cache Info or MismatchInfo
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

                val artistMatch = cachedArtist == artist
                val titleMatch = cachedTitle == title
                val durationMatch = Math.abs(cachedDuration - durationMs) <= DURATION_TOLERANCE_MS

                artistMatch && titleMatch && durationMatch
            } catch (e: Exception) {
                Log.e(TAG, "Error reading cache file ${file.name}", e)
                false
            }
        }
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

        val filesWithAccessTime = files.mapNotNull { file ->
            try {
                val json = JSONObject(file.readText())
                val lastAccessed = json.optLong("lastAccessed", 0L)
                Pair(file, lastAccessed)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading file ${file.name}", e)
                null
            }
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
            .replace(Regex("[^a-z0-9\\s]"), "") // Remove special chars
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
