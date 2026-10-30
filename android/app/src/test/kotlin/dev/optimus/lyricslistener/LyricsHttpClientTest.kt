package dev.optimus.lyricslistener

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LyricsHttpClientTest {
    @Serializable
    data class Response(val trackName: String, val syncedLyrics: String)

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
    fun androidEngineDecodesJsonAndPlainTextProviderResponses() = runBlocking {
        Provider().use { server ->
            client().use { http ->
                withTimeout(5_000) {
                    for (route in listOf("json", "plain")) {
                        val lyrics = http.get("${server.url}/$route").body<Response>()
                        assertEquals("夜に駆ける", lyrics.trackName)
                        assertEquals("[00:12.345] Hello", lyrics.syncedLyrics)
                    }
                }
            }
        }
    }

    @Test
    fun cancellingAProviderRequestAllowsTheNextSongToLoad() = runBlocking {
        Provider().use { server ->
            client().use { http ->
                val oldRequest = async { http.get("${server.url}/slow").body<Response>() }
                withTimeout(5_000) {
                    while (server.slowRequestStarted.count != 0L) delay(10)
                    oldRequest.cancelAndJoin()
                    assertTrue(oldRequest.isCancelled)
                    assertEquals("夜に駆ける", http.get("${server.url}/next").body<Response>().trackName)
                }
            }
        }
    }

    /** A loopback HTTP fixture exercises the real Android engine without an external provider. */
    private class Provider : Closeable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val releaseSlowRequest = CountDownLatch(1)
        val slowRequestStarted = CountDownLatch(1)
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
                                    val route = reader.readLine().split(' ')[1]
                                    while (!reader.readLine().isNullOrEmpty()) { /* Read request headers. */ }
                                    if (route == "/slow") {
                                        slowRequestStarted.countDown()
                                        releaseSlowRequest.await(5, TimeUnit.SECONDS)
                                        return@execute
                                    }
                                    val type = if (route == "/plain") "text/plain" else "application/json"
                                    val body = """{"trackName":"夜に駆ける","syncedLyrics":"[00:12.345] Hello","extra":true}"""
                                        .toByteArray(Charsets.UTF_8)
                                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: $type; charset=utf-8\r\n" +
                                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                    connection.getOutputStream().apply {
                                        write(headers.toByteArray(Charsets.US_ASCII))
                                        write(body)
                                        flush()
                                    }
                                } catch (_: IOException) {
                                    // Cancellation may disconnect a request before the fixture responds.
                                } catch (_: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                }
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Closing the fixture releases the accept call.
                }
            }
        }

        override fun close() {
            releaseSlowRequest.countDown()
            socket.close()
            executor.shutdownNow()
        }
    }
}
