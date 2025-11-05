package dev.optimus.lyricslistener

/**
 * Represents different types of lyrics data that can be displayed.
 */
sealed class LyricsData {
    abstract val durationMs: Long

    /**
     * Plain text lyrics (non-synced)
     */
    data class Plain(
        val title: String,
        val artist: String?,
        val lyrics: String,
        override val durationMs: Long
    ) : LyricsData()

    /**
     * Synced lyrics with timestamps
     */
    data class Synced(
        val title: String,
        val artist: String?,
        val lines: List<TimedLyricLine>,
        val translatedLines: List<TimedLyricLine>? = null,
        override val durationMs: Long
    ) : LyricsData()

    /**
     * Status messages (loading, not found, etc)
     */
    data class Info(
        val title: String?,
        val artist: String?,
        val message: String,
        override val durationMs: Long
    ) : LyricsData()

    /**
     * Indicates potential song mismatch from LRCLib
     */
    data class MismatchInfo(
        val title: String?,
        val artist: String?,
        val originalLyricsData: LyricsData?
    ) : LyricsData() {
        override val durationMs: Long
            get() = originalLyricsData?.durationMs ?: 0L
    }
}

/**
 * Represents a single lyric line with timestamp.
 */
data class TimedLyricLine(
    val timestamp: Long,
    val text: String
)
