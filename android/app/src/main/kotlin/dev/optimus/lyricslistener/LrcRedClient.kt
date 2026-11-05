package dev.optimus.lyricslistener

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.abs

@Serializable
internal data class LrcRedSearchResponse(
    val hits: List<LrcRedHit> = emptyList(),
    val next: String? = null
)

@Serializable
internal data class LrcRedHit(
    val isrc: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val year: Int? = null,
    val duration: Double? = null
)

internal data class LrcRedFetchResult(
    val displayLyrics: LyricsData.Synced,
    val rawLinesForCache: List<TimedLyricLine>,
    val durationMs: Long
)

internal object LrcRedClient {
    private const val TAG = "LrcRedClient"
    const val DEFAULT_BASE_URL = "https://lrc.red"
    const val CACHE_SOURCE = "lrc.red"
    const val ATTRIBUTION = "Lyrics provided by lrc.red"
    const val USER_AGENT =
        "LyricListener/3.9.0 (https://github.com/Optimuspime123/LyricsListener; adrestaia47@gmail.com)"

    private val WORD_TIMESTAMP_REGEX = Regex("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>")
    private val MULTI_SPACE_REGEX = Regex(" {2,}")
    private val SAFE_ISRC_REGEX = Regex("^[A-Za-z0-9_-]{5,32}$")
    private val PARENTHETICAL_SUFFIX_REGEX = Regex("\\s*[(\\[{].*?[)\\]}]\\s*")
    private val ARTIST_SPLIT_REGEX = Regex(
        ",|feat\\.?|ft\\.?|featuring|&|\\||\\s+x\\s+|\\band\\b|\\bwith\\b|\\bvs\\.?\\b",
        RegexOption.IGNORE_CASE
    )
    private val LRC_LINE_PATTERN: Pattern =
        Pattern.compile("(?<=\\[)(\\d{2,}):(\\d{2})([.:])(\\d{2,3})\\](.*)")

    /**
     * Strips Enhanced LRC inline word-sync tags (`<mm:ss.xx>`) while preserving syllable joins
     * and collapsing accidental double spaces.
     */
    fun cleanEnhancedLrc(rawLrc: String): String {
        if (rawLrc.isEmpty() || !rawLrc.contains('<')) return rawLrc
        return rawLrc.lineSequence()
            .map { line ->
                line.replace(WORD_TIMESTAMP_REGEX, "")
                    .replace(MULTI_SPACE_REGEX, " ")
            }
            .joinToString("\n")
    }

    fun rankMatchingHits(
        hits: List<LrcRedHit>,
        queryTitle: String,
        queryArtist: String,
        durationFromMediaMs: Long,
        isFromYouTube: Boolean = false,
        durationToleranceSec: Double = 3.0
    ): List<LrcRedHit> {
        if (hits.isEmpty() || queryTitle.isBlank()) return emptyList()

        val mediaDurationSec = if (durationFromMediaMs > 0L) durationFromMediaMs / 1000.0 else -1.0
        val normQueryTitle = normalizeForComparison(queryTitle)
        val baseQueryTitle = normalizeForComparison(stripEditionSuffixes(queryTitle))
        val normQueryArtist = normalizeForComparison(queryArtist)
        val primaryQueryArtist = normalizeForComparison(extractPrimaryArtist(queryArtist))
        val youtubeCombined = normalizeForComparison("$queryTitle $queryArtist")

        if (normQueryTitle.isEmpty()) return emptyList()

        data class ScoredHit(val hit: LrcRedHit, val score: Int, val durationDeltaSec: Double)

        val candidates = mutableListOf<ScoredHit>()
        for (hit in hits) {
            val isrc = hit.isrc.trim()
            if (!SAFE_ISRC_REGEX.matches(isrc)) continue

            val hitDurationSec = hit.duration ?: -1.0
            val durationDeltaSec = if (mediaDurationSec > 0.0) {
                if (hitDurationSec <= 0.0) continue
                val delta = abs(hitDurationSec - mediaDurationSec)
                if (delta > durationToleranceSec) continue
                delta
            } else {
                0.0
            }

            val normHitTitle = normalizeForComparison(hit.title)
            val baseHitTitle = normalizeForComparison(stripEditionSuffixes(hit.title))
            val normHitArtist = normalizeForComparison(hit.artist)
            val primaryHitArtist = normalizeForComparison(extractPrimaryArtist(hit.artist))

            if (normHitTitle.isEmpty()) continue

            if (mediaDurationSec <= 0.0) {
                // Without player duration, require strict normalized title & artist agreement.
                val strictTitle = normHitTitle == normQueryTitle ||
                    (baseHitTitle.isNotEmpty() && baseHitTitle == baseQueryTitle)
                val strictArtist = normQueryArtist.isEmpty() ||
                    normHitArtist == normQueryArtist ||
                    (primaryHitArtist.isNotEmpty() && primaryHitArtist == primaryQueryArtist)
                if (!strictTitle || !strictArtist) continue
            }

            var score = 0
            if (isFromYouTube) {
                val targetTitle = baseHitTitle.ifEmpty { normHitTitle }
                val titleMatched = youtubeCombined == normHitTitle ||
                    (targetTitle.length >= 3 && containsWholePhrase(youtubeCombined, targetTitle)) ||
                    hasStrongWordOverlap(youtubeCombined, targetTitle)
                if (!titleMatched) continue
                score += if (containsWholePhrase(youtubeCombined, normHitTitle)) 4 else 2
                if (primaryHitArtist.length >= 3 && containsWholePhrase(youtubeCombined, primaryHitArtist)) {
                    score += 3
                }
            } else {
                val exactTitle = normHitTitle == normQueryTitle
                val exactBaseTitle = baseHitTitle.isNotEmpty() &&
                    baseQueryTitle.isNotEmpty() &&
                    baseHitTitle == baseQueryTitle
                val relaxedTitle = (baseHitTitle.length >= 4 && baseQueryTitle.length >= 4 &&
                    (containsWholePhrase(baseHitTitle, baseQueryTitle) ||
                        containsWholePhrase(baseQueryTitle, baseHitTitle))) ||
                    hasStrongWordOverlap(baseHitTitle.ifEmpty { normHitTitle }, baseQueryTitle.ifEmpty { normQueryTitle })

                if (!exactTitle && !exactBaseTitle && !relaxedTitle) continue
                score += when {
                    exactTitle -> 6
                    exactBaseTitle -> 4
                    else -> 2
                }

                if (normQueryArtist.isNotEmpty()) {
                    val exactArtist = normHitArtist == normQueryArtist
                    val hitArtistParts = splitArtistsNormalized(hit.artist)
                    val queryArtistParts = splitArtistsNormalized(queryArtist)
                    val primaryMatch = primaryHitArtist.isNotEmpty() &&
                        primaryQueryArtist.isNotEmpty() &&
                        (primaryHitArtist == primaryQueryArtist ||
                            hitArtistParts.contains(primaryQueryArtist) ||
                            queryArtistParts.contains(primaryHitArtist))
                    val relaxedArtist = (primaryHitArtist.length >= 4 && primaryQueryArtist.length >= 4 &&
                        (containsWholePhrase(primaryHitArtist, primaryQueryArtist) ||
                            containsWholePhrase(primaryQueryArtist, primaryHitArtist))) ||
                        hasStrongWordOverlap(normHitArtist, normQueryArtist)

                    if (!exactArtist && !primaryMatch && !relaxedArtist) continue
                    score += when {
                        exactArtist -> 6
                        primaryMatch -> 4
                        else -> 2
                    }
                }
            }

            candidates.add(ScoredHit(hit.copy(isrc = isrc), score, durationDeltaSec))
        }

        return candidates
            .sortedWith(compareByDescending<ScoredHit> { it.score }.thenBy { it.durationDeltaSec })
            .map { it.hit }
    }

    fun selectBestHit(
        hits: List<LrcRedHit>,
        queryTitle: String,
        queryArtist: String,
        durationFromMediaMs: Long,
        isFromYouTube: Boolean = false,
        durationToleranceSec: Double = 3.0
    ): LrcRedHit? = rankMatchingHits(
        hits = hits,
        queryTitle = queryTitle,
        queryArtist = queryArtist,
        durationFromMediaMs = durationFromMediaMs,
        isFromYouTube = isFromYouTube,
        durationToleranceSec = durationToleranceSec
    ).firstOrNull()

    suspend fun fetchLyrics(
        httpClient: HttpClient,
        sourceAccess: LyricsSourceAccess,
        title: String,
        artist: String,
        durationFromMediaMs: Long,
        isFromYouTube: Boolean = false,
        cleanedYouTubeQuery: String? = null,
        enableFallbackRetries: Boolean = true,
        baseUrl: String = DEFAULT_BASE_URL,
        parseLrcLines: (String) -> List<TimedLyricLine> = ::defaultParseSyncedLyrics
    ): LrcRedFetchResult? {
        if (title.isBlank()) return null

        val trimmedBaseUrl = baseUrl.trimEnd('/')
        val initialQuery = if (isFromYouTube) {
            cleanedYouTubeQuery?.takeIf { it.isNotBlank() } ?: title.trim()
        } else {
            "$title $artist".trim()
        }

        val attemptedQueries = mutableSetOf<String>()
        var candidates = emptyList<LrcRedHit>()

        if (initialQuery.isNotBlank() && attemptedQueries.add(initialQuery.lowercase())) {
            val hits = searchHits(httpClient, sourceAccess, trimmedBaseUrl, initialQuery)
            if (hits != null) {
                candidates = rankMatchingHits(
                    hits = hits,
                    queryTitle = if (isFromYouTube) initialQuery else title,
                    queryArtist = artist,
                    durationFromMediaMs = durationFromMediaMs,
                    isFromYouTube = isFromYouTube
                )
            }
        }

        if (candidates.isEmpty() && !isFromYouTube && enableFallbackRetries) {
            val primaryArtist = extractPrimaryArtist(artist)
            val fallbackQuery = "$title $primaryArtist".trim()
            if (primaryArtist.isNotBlank() &&
                fallbackQuery.isNotBlank() &&
                attemptedQueries.add(fallbackQuery.lowercase())
            ) {
                Log.d(TAG, "lrc.red: Retrying search for '$title' with primary artist '$primaryArtist'.")
                val fallbackHits = searchHits(httpClient, sourceAccess, trimmedBaseUrl, fallbackQuery)
                if (fallbackHits != null) {
                    candidates = rankMatchingHits(
                        hits = fallbackHits,
                        queryTitle = title,
                        queryArtist = primaryArtist,
                        durationFromMediaMs = durationFromMediaMs,
                        isFromYouTube = false
                    )
                }
            }
        }

        if (candidates.isEmpty()) {
            Log.d(TAG, "lrc.red: No matching track candidate found for '$title' by '$artist'.")
            return null
        }

        for (candidate in candidates.take(3)) {
            if (!currentCoroutineContext().isActive) return null
            val rawLrc = downloadLrcFile(httpClient, sourceAccess, trimmedBaseUrl, candidate.isrc)
                ?: continue
            val cleanedLrc = cleanEnhancedLrc(rawLrc)
            val timedLines = parseLrcLines(cleanedLrc)
                .filter { it.timestamp != LyricService.ATTRIBUTION_TIMESTAMP }

            val hasRealLyricText = timedLines.any { line ->
                line.text.isNotBlank() && line.text != "🎶 ... 🎶"
            }
            if (!hasRealLyricText) {
                Log.d(TAG, "lrc.red: ISRC ${candidate.isrc} had no usable timed lines.")
                continue
            }

            val hitDurationMs = ((candidate.duration ?: 0.0) * 1000.0).toLong()
            val finalDurationMs = when {
                durationFromMediaMs > 0L -> durationFromMediaMs
                hitDurationMs > 0L -> hitDurationMs
                else -> 0L
            }

            val linesWithAttribution = timedLines.toMutableList().apply {
                add(TimedLyricLine(LyricService.ATTRIBUTION_TIMESTAMP, ATTRIBUTION))
            }

            val syncedData = LyricsData.Synced(
                title = title,
                artist = artist.ifEmpty { null },
                lines = linesWithAttribution,
                translatedLines = null,
                durationMs = finalDurationMs
            )

            Log.d(TAG, "lrc.red: Loaded ${timedLines.size} synced lines for '$title' (ISRC=${candidate.isrc}).")
            return LrcRedFetchResult(
                displayLyrics = syncedData,
                rawLinesForCache = timedLines,
                durationMs = finalDurationMs
            )
        }

        return null
    }

    private suspend fun searchHits(
        httpClient: HttpClient,
        sourceAccess: LyricsSourceAccess,
        baseUrl: String,
        query: String
    ): List<LrcRedHit>? {
        if (!currentCoroutineContext().isActive) return null
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val url = "$baseUrl/search.json?q=$encodedQuery"
        return try {
            sourceAccess.request(LyricsSource.LRCRED) {
                Log.d(TAG, "Fetching search results from lrc.red: $url")
                val response = httpClient.get(url) {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                }
                if (!response.status.isSuccess()) {
                    Log.d(TAG, "lrc.red search returned HTTP ${response.status.value} for '$query'")
                    null
                } else {
                    response.body<LrcRedSearchResponse>().hits
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "lrc.red search exception for '$query': ${e.message}")
            null
        }
    }

    private suspend fun downloadLrcFile(
        httpClient: HttpClient,
        sourceAccess: LyricsSourceAccess,
        baseUrl: String,
        isrc: String
    ): String? {
        val url = "$baseUrl/s/$isrc.lrc"
        return try {
            sourceAccess.request(LyricsSource.LRCRED) {
                Log.d(TAG, "Fetching LRC file from lrc.red: $url")
                val response = httpClient.get(url) {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                }
                if (!response.status.isSuccess()) {
                    Log.d(TAG, "lrc.red LRC file returned HTTP ${response.status.value} for ISRC=$isrc")
                    null
                } else {
                    response.bodyAsText(Charsets.UTF_8)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "lrc.red LRC download exception for ISRC=$isrc: ${e.message}")
            null
        }
    }

    internal fun defaultParseSyncedLyrics(syncedLyricsText: String): List<TimedLyricLine> {
        if (syncedLyricsText.isBlank()) return emptyList()
        val lines = mutableListOf<TimedLyricLine>()
        syncedLyricsText.lines().forEach { lineContent ->
            var currentTextSegment = lineContent.trim()
            val tempLines = mutableListOf<TimedLyricLine>()
            while (currentTextSegment.startsWith("[")) {
                val matcher = LRC_LINE_PATTERN.matcher(currentTextSegment)
                if (matcher.find() && matcher.start() == 1) {
                    val minutes = matcher.group(1)?.toIntOrNull() ?: break
                    val seconds = matcher.group(2)?.toIntOrNull() ?: break
                    val millisStr = matcher.group(4) ?: break
                    val textFollowingTag = matcher.group(5) ?: ""
                    val milliseconds = if (millisStr.length == 2) millisStr.toInt() * 10 else millisStr.toInt()
                    val timestamp = TimeUnit.MINUTES.toMillis(minutes.toLong()) +
                        TimeUnit.SECONDS.toMillis(seconds.toLong()) +
                        milliseconds.toLong()
                    tempLines.add(TimedLyricLine(timestamp, ""))
                    currentTextSegment = textFollowingTag.trimStart()
                } else {
                    break
                }
            }
            if (tempLines.isNotEmpty()) {
                val finalLyricText = currentTextSegment.trim()
                val displayText = if (finalLyricText.isBlank()) "🎶 ... 🎶" else finalLyricText
                tempLines.forEach { placeholder ->
                    lines.add(placeholder.copy(text = displayText))
                }
            }
        }
        return lines.sortedBy { it.timestamp }
    }

    private fun stripEditionSuffixes(value: String): String {
        return value
            .replace(PARENTHETICAL_SUFFIX_REGEX, " ")
            .substringBefore(" - ")
            .trim()
    }

    private fun extractPrimaryArtist(artist: String): String {
        return artist
            .split(ARTIST_SPLIT_REGEX)
            .firstOrNull()
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
    }

    private fun splitArtistsNormalized(artist: String): Set<String> {
        return artist
            .split(ARTIST_SPLIT_REGEX)
            .map { normalizeForComparison(it) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun normalizeForComparison(str: String): String {
        return Normalizer.normalize(str, Normalizer.Form.NFC)
            .trim()
            .lowercase()
            .replace(Regex("\\s+and\\s+"), " ")
            .replace(Regex("\\s+with\\s+"), " ")
            .replace(Regex("\\s*&\\s*"), " ")
            .replace(Regex("\\s*,\\s*"), " ")
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun containsWholePhrase(haystack: String, needle: String): Boolean {
        if (haystack.isEmpty() || needle.isEmpty()) return false
        if (haystack == needle) return true
        return " $haystack ".contains(" $needle ")
    }

    private fun hasStrongWordOverlap(a: String, b: String): Boolean {
        val wordsA = a.split(' ').filter { it.length > 2 }.toSet()
        val wordsB = b.split(' ').filter { it.length > 2 }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        val overlap = wordsA.intersect(wordsB).size
        val minSize = minOf(wordsA.size, wordsB.size)
        return overlap >= 1 && overlap.toDouble() / minSize >= 0.7
    }
}
