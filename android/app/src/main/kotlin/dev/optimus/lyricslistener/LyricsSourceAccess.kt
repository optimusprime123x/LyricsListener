package dev.optimus.lyricslistener

internal enum class LyricsSource(
    val preferenceKey: String,
    val label: String,
    val shortLabel: String = label
) {
    LRCLIB("lyrics_source_lrclib_enabled", "LRCLib"),
    MUSIXMATCH("lyrics_source_musixmatch_enabled", "Musixmatch"),
    LRCRED("lyrics_source_lrcred_enabled", "lrc.red"),
    OTHERS("lyrics_source_others_enabled", "Others (Apple Music, Netease, Deezer)", "Others");

    companion object {
        fun fromSourceIdentifier(rawSource: String?, durationMs: Long = 1L): LyricsSource? {
            val normalized = rawSource?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty() || normalized == "cache" || normalized == "unknown") {
                return null
            }
            return when (normalized) {
                "lrclib" -> LRCLIB
                "musixmatch" -> MUSIXMATCH
                "lrc.red", "lrcred" -> LRCRED
                "custom" -> if (durationMs <= 0L) null else OTHERS
                else -> OTHERS
            }
        }

        fun fromAttributionText(attributionText: String?, durationMs: Long = 1L): LyricsSource? {
            val trimmed = attributionText?.trim().orEmpty()
            if (!trimmed.startsWith("Lyrics provided by ", ignoreCase = true)) {
                return null
            }
            val rawSource = trimmed
                .substring("Lyrics provided by ".length)
                .replace(Regex("\\s*\\(cached\\)\\s*$", RegexOption.IGNORE_CASE), "")
                .trim()
            return fromSourceIdentifier(rawSource, durationMs)
        }
    }
}

internal data class RetrySourceOption(
    val source: LyricsSource,
    val displayText: String,
    val isCurrent: Boolean,
    val isTappable: Boolean
)

/** Reads current preferences for every request, including retries and token acquisition. */
internal class LyricsSourceAccess(
    private val readPreference: (String) -> Boolean?,
    private val logSkipped: (LyricsSource) -> Unit
) {
    fun isEnabled(source: LyricsSource): Boolean = readPreference(source.preferenceKey) ?: true

    fun selection(): List<Boolean> = LyricsSource.entries.map(::isEnabled)

    fun canFetch(source: LyricsSource): Boolean = isEnabled(source).also {
        if (!it) logSkipped(source)
    }

    fun retryOptions(currentSource: LyricsSource?): List<RetrySourceOption> {
        return LyricsSource.entries
            .filter { source -> isEnabled(source) || source == currentSource }
            .map { source ->
                val isCurrent = source == currentSource
                RetrySourceOption(
                    source = source,
                    displayText = if (isCurrent) "${source.shortLabel} (current)" else source.shortLabel,
                    isCurrent = isCurrent,
                    isTappable = !isCurrent && isEnabled(source)
                )
            }
    }

    suspend fun <T> request(source: LyricsSource, fetch: suspend () -> T): T? {
        if (!canFetch(source)) return null
        val result = fetch()
        // A setting can change while an HTTP request is in flight. Do not use that response.
        return if (canFetch(source)) result else null
    }
}
