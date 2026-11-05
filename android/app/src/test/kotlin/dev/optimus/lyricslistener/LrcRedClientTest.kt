package dev.optimus.lyricslistener

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class LrcRedClientTest {

    private fun client() = HttpClient(Android) {
        install(ContentNegotiation) {
            val converter = KotlinxSerializationConverter(Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            })
            register(ContentType.Application.Json, converter)
            register(ContentType.Text.Plain, converter)
        }
    }

    @Test
    fun cleanEnhancedLrcStripsInlineWordTimestampsAndPreservesSyllableSpacing() {
        val raw = """
            [ti:Creep]
            [ar:Radiohead]
            [00:19.76]<00:19.76>When <00:20.11>you were <00:20.33>here <00:20.61>be<00:20.90>fore <00:21.90>
            [00:24.10]Couldn't look you in the eye
            [01:08.53]
        """.trimIndent()

        val cleaned = LrcRedClient.cleanEnhancedLrc(raw)
        val parsed = LrcRedClient.defaultParseSyncedLyrics(cleaned)

        assertEquals(3, parsed.size)
        assertEquals(TimedLyricLine(19_760L, "When you were here before"), parsed[0])
        assertEquals(TimedLyricLine(24_100L, "Couldn't look you in the eye"), parsed[1])
        assertEquals(TimedLyricLine(68_530L, "🎶 ... 🎶"), parsed[2])
    }

    @Test
    fun selectBestHitRejectsUnrelatedSubstringHitsAndPicksExactDurationMatch() {
        val hits = listOf(
            LrcRedHit(
                isrc = "GBKPL2298397",
                title = "Following the Sun",
                artist = "SUPER-Hi, Neeka",
                duration = 205.512
            ),
            LrcRedHit(
                isrc = "NOG842601070",
                title = "Void",
                artist = "Alan Walker, Sorana",
                duration = 191.861
            )
        )

        // Substring match on artist "SUPER-Hi" must not match an unrelated song "Hi" by "Other Artist"
        assertNull(
            LrcRedClient.selectBestHit(
                hits = hits,
                queryTitle = "Hi",
                queryArtist = "Other Artist",
                durationFromMediaMs = 205_500L
            )
        )

        val matched = LrcRedClient.selectBestHit(
            hits = hits,
            queryTitle = "Void",
            queryArtist = "Alan Walker & Sorana",
            durationFromMediaMs = 192_000L
        )
        assertNotNull(matched)
        assertEquals("NOG842601070", matched!!.isrc)
    }

    @Test
    fun fetchLyricsQueriesSearchJsonDownloadsLrcSendsUserAgentAndAppendsAttribution() = runBlocking {
        LrcRedFixture().use { server ->
            client().use { http ->
                withTimeout(5_000) {
                    val access = LyricsSourceAccess({ true }, {})
                    val result = LrcRedClient.fetchLyrics(
                        httpClient = http,
                        sourceAccess = access,
                        title = "Void",
                        artist = "Alan Walker & Sorana",
                        durationFromMediaMs = 191_861L,
                        baseUrl = server.url
                    )

                    assertNotNull(result)
                    val lines = result!!.displayLyrics.lines
                    assertEquals(3, lines.size)
                    assertEquals(TimedLyricLine(10_250L, "Into the void"), lines[0])
                    assertEquals(TimedLyricLine(14_500L, "Are you listening"), lines[1])
                    assertEquals(
                        TimedLyricLine(LyricService.ATTRIBUTION_TIMESTAMP, "Lyrics provided by lrc.red"),
                        lines[2]
                    )
                    assertEquals(2, result.rawLinesForCache.size)
                    assertTrue(server.userAgents.all { it == LrcRedClient.USER_AGENT })
                    assertTrue(server.routes.any { it.startsWith("/search.json?q=") })
                    assertTrue(server.routes.contains("/s/NOG842601070.lrc"))
                }
            }
        }
    }

    @Test
    fun fetchLyricsFallsBackToSecondCandidateWhenFirstLrcReturns404() = runBlocking {
        LrcRedFixture().use { server ->
            client().use { http ->
                withTimeout(5_000) {
                    val access = LyricsSourceAccess({ true }, {})
                    val result = LrcRedClient.fetchLyrics(
                        httpClient = http,
                        sourceAccess = access,
                        title = "MissingFirst",
                        artist = "Test Artist",
                        durationFromMediaMs = 180_000L,
                        baseUrl = server.url
                    )

                    assertNotNull(result)
                    assertEquals("Fallback line", result!!.rawLinesForCache.single().text)
                }
            }
        }
    }

    private class LrcRedFixture : Closeable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        val routes = CopyOnWriteArrayList<String>()
        val userAgents = CopyOnWriteArrayList<String>()
        val url = "http://127.0.0.1:${socket.localPort}"

        init {
            executor.execute {
                try {
                    while (!socket.isClosed) {
                        val connection = socket.accept()
                        executor.execute {
                            connection.use {
                                try {
                                    connection.soTimeout = 5_000
                                    val reader = connection.getInputStream().bufferedReader()
                                    val requestLine = reader.readLine() ?: return@execute
                                    val route = requestLine.split(' ')[1]
                                    routes.add(route)
                                    while (true) {
                                        val headerLine = reader.readLine()
                                        if (headerLine.isNullOrEmpty()) break
                                        if (headerLine.startsWith("User-Agent:", ignoreCase = true)) {
                                            userAgents.add(headerLine.substringAfter(':').trim())
                                        }
                                    }

                                    val (status, contentType, payload) = when {
                                        route.startsWith("/search.json") && route.contains("MissingFirst") -> Triple(
                                            "200 OK",
                                            "application/json",
                                            """{"hits":[
                                                {"isrc":"MISSING00001","title":"MissingFirst","artist":"Test Artist","duration":180.0},
                                                {"isrc":"PRESENT00002","title":"MissingFirst","artist":"Test Artist","duration":180.2}
                                            ],"next":null}"""
                                        )
                                        route.startsWith("/search.json") -> Triple(
                                            "200 OK",
                                            "application/json",
                                            """{"hits":[{"isrc":"NOG842601070","title":"Void","artist":"Alan Walker, Sorana","duration":191.861}],"next":null}"""
                                        )
                                        route == "/s/NOG842601070.lrc" -> Triple(
                                            "200 OK",
                                            "text/plain",
                                            "[00:10.25]<00:10.25>Into <00:10.60>the <00:10.95>void\n[00:14.50]Are you listening\n"
                                        )
                                        route == "/s/PRESENT00002.lrc" -> Triple(
                                            "200 OK",
                                            "text/plain",
                                            "[00:05.00]Fallback line\n"
                                        )
                                        else -> Triple("404 Not Found", "text/plain", "Not Found")
                                    }

                                    val body = payload.toByteArray(Charsets.UTF_8)
                                    val headers = "HTTP/1.1 $status\r\nContent-Type: $contentType; charset=utf-8\r\n" +
                                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                    connection.getOutputStream().apply {
                                        write(headers.toByteArray(Charsets.US_ASCII))
                                        write(body)
                                        flush()
                                    }
                                } catch (_: IOException) {
                                }
                            }
                        }
                    }
                } catch (_: IOException) {
                }
            }
        }

        override fun close() {
            socket.close()
            executor.shutdownNow()
        }
    }
}
