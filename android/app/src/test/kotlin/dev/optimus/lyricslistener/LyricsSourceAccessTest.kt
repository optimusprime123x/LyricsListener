package dev.optimus.lyricslistener

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LyricsSourceAccessTest {
    @Test fun existingInstallationsKeepAllSourcesEnabled() = runBlocking {
        val access = LyricsSourceAccess({ null }, { fail("Unexpected skip: $it") })
        for (source in LyricsSource.entries) {
            assertTrue(access.isEnabled(source))
            assertEquals(source.label, access.request(source) { source.label })
        }
    }

    @Test fun everyToggleCombinationSkipsOnlyDisabledRequestsAndLogsThem() = runBlocking {
        for (mask in 0..7) {
            val preferences = LyricsSource.entries.mapIndexed { index, source ->
                source.preferenceKey to (mask and (1 shl index) != 0)
            }.toMap()
            val skipped = mutableListOf<LyricsSource>()
            val calls = mutableListOf<LyricsSource>()
            val access = LyricsSourceAccess(preferences::get, skipped::add)
            for (source in LyricsSource.entries) {
                val result = access.request(source) { calls.add(source); source.label }
                assertEquals(preferences[source.preferenceKey] == true, result != null)
            }
            assertEquals(LyricsSource.entries.filter { preferences[it.preferenceKey] == true }, calls)
            assertEquals(LyricsSource.entries.filter { preferences[it.preferenceKey] == false }, skipped)
        }
    }

    @Test fun changingPreferencesBlocksQueuedRetriesWithoutRestartingTheService() = runBlocking {
        val preferences = mutableMapOf<String, Boolean>()
        val skipped = mutableListOf<LyricsSource>()
        val access = LyricsSourceAccess(preferences::get, skipped::add)
        val source = LyricsSource.LRCLIB
        val previousSelection = access.selection()
        assertEquals("first attempt", access.request(source) { "first attempt" })
        preferences[source.preferenceKey] = false
        assertNotEquals(previousSelection, access.selection())
        assertNull(access.request(source) { fail("Disabled LRCLib fallback made a request") })
        assertEquals(listOf(source), skipped)
        preferences[source.preferenceKey] = true
        assertEquals(previousSelection, access.selection())
        assertEquals("enabled again", access.request(source) { "enabled again" })
    }

    @Test fun disabledMusixmatchAlsoBlocksTokenAcquisition() = runBlocking {
        val access = LyricsSourceAccess({ false }, {})
        assertNull(access.request(LyricsSource.MUSIXMATCH) { fail("Token endpoint was called") })
    }

    @Test fun disablingASourceDiscardsItsInFlightResultAndStopsSubsequentCalls() = runBlocking {
        var enabled = true
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val skipped = mutableListOf<LyricsSource>()
        val access = LyricsSourceAccess({ enabled }, skipped::add)
        val response = async {
            access.request(LyricsSource.OTHERS) {
                started.complete(Unit)
                finish.await()
                "lyrics that must not be displayed or cached"
            }
        }
        started.await()
        enabled = false
        finish.complete(Unit)
        assertNull(response.await())
        assertNull(access.request(LyricsSource.OTHERS) { fail("A disabled source was requested") })
        assertEquals(listOf(LyricsSource.OTHERS, LyricsSource.OTHERS), skipped)
    }

    @Test fun requestCancellationIsNotConvertedToAProviderResult() = runBlocking {
        val access = LyricsSourceAccess({ true }, {})
        try {
            access.request(LyricsSource.LRCLIB) { throw CancellationException("Song changed") }
            fail("Cancellation should propagate")
        } catch (expected: CancellationException) {
            assertEquals("Song changed", expected.message)
        }
    }
}
