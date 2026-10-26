package dev.optimus.lyricslistener

internal enum class LyricsSource(val preferenceKey: String, val label: String) {
    LRCLIB("lyrics_source_lrclib_enabled", "LRCLib"),
    MUSIXMATCH("lyrics_source_musixmatch_enabled", "Musixmatch"),
    OTHERS("lyrics_source_others_enabled", "Others (Apple Music, Netease, Deezer)")
}

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

    suspend fun <T> request(source: LyricsSource, fetch: suspend () -> T): T? {
        if (!canFetch(source)) return null
        val result = fetch()
        // A setting can change while an HTTP request is in flight. Do not use that response.
        return if (canFetch(source)) result else null
    }
}
