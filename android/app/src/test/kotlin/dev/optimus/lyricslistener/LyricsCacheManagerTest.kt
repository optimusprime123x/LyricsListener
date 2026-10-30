package dev.optimus.lyricslistener

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LyricsCacheManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun synced(title: String, text: String = "Hello", duration: Long = 0L) =
        LyricsData.Synced(title, "Artist", listOf(TimedLyricLine(12345, text)), durationMs = duration)

    @Test fun unicodeTitlesAndArtistsSurviveSaveAndReload() {
        val directory = temporary.newFolder()
        val cache = LyricsCacheManager(directory)
        for ((title, artist) in listOf("तुम ही हो" to "अरिजीत सिंह", "夜に駆ける" to "YOASOBI", "Привет" to "Певец")) {
            assertTrue(cache.put(artist, title, 0, synced(title), "custom"))
            val reloaded = LyricsCacheManager(directory).get(artist, title, 180000) as LyricsData.Synced
            assertEquals(title, reloaded.title)
            assertEquals(12345L, reloaded.lines.first().timestamp)
        }
    }

    @Test fun legacyMetadataIsRebuiltWithoutRenamingOrDeletingExistingLyrics() {
        val directory = temporary.newFolder()
        val originalFile = File(directory, "old-key.json").apply {
            writeText(JSONObject().apply {
                put("type", "synced")
                put("title", "Déjà vu")
                put("artist", "Beyoncé")
                put("durationMs", 180000)
                put("source", "custom")
                put("lines", JSONArray().put(JSONObject().put("timestamp", 12345).put("text", "Original")))
            }.toString())
        }
        File(directory, "metadata.json").writeText(JSONObject().apply {
            put("version", 1)
            put("entries", JSONArray().put(JSONObject().apply {
                put("cacheKey", "old-key")
                put("title", "dj vu")
                put("artist", "beyonc")
                put("durationMs", 180000)
            }))
        }.toString())

        val cache = LyricsCacheManager(directory)
        val lyrics = cache.get("Beyonce\u0301", "De\u0301ja\u0300 vu", 180000) as LyricsData.Synced
        assertEquals("Original", lyrics.lines.first().text)
        assertTrue(originalFile.exists())
        assertEquals("old-key", cache.findExactEntries("Beyoncé", "Déjà vu").single()["cacheKey"])
    }

    @Test fun replacingCustomLyricsKeepsTheNewZeroDurationEntryAndRemovesOldVersions() {
        val directory = temporary.newFolder()
        val cache = LyricsCacheManager(directory)
        assertTrue(cache.put("Artist", "Song", 180000, synced("Song", "Downloaded", 180000), "lrclib"))
        assertTrue(cache.put("Artist", "Song", 0, synced("Song", "Previous custom"), "custom"))
        assertTrue(cache.put("Artist", "Song", 0, synced("Song", "Replacement"), "custom", replaceExactMatches = true))

        val reloaded = LyricsCacheManager(directory)
        assertEquals(1, reloaded.findExactEntries("Artist", "Song").size)
        assertEquals("Replacement", (reloaded.get("Artist", "Song", 180000) as LyricsData.Synced).lines.first().text)
    }

    @Test fun failedReplacementDoesNotDeleteOrTruncatePreviousLyrics() {
        val directory = temporary.newFolder()
        var failWrites = false
        val cache = LyricsCacheManager(directory) { file, content ->
            if (failWrites) throw IOException("Disk unavailable")
            writeLyricsFileAtomically(file, content)
        }
        assertTrue(cache.put("Artist", "Song", 180000, synced("Song", "Downloaded", 180000), "lrclib"))
        assertTrue(cache.put("Artist", "Song", 0, synced("Song", "Previous custom"), "custom"))
        val filesBefore = directory.listFiles()!!.associate { it.name to it.readText() }

        failWrites = true
        assertFalse(cache.put("Artist", "Song", 0, synced("Song", "Replacement"), "custom", replaceExactMatches = true))
        assertEquals(filesBefore, directory.listFiles()!!.associate { it.name to it.readText() })
    }

    @Test fun metadataCommitFailureIsReportedAndKeepsTheEntriesBeingReplaced() {
        val directory = temporary.newFolder()
        var failMetadata = false
        val cache = LyricsCacheManager(directory) { file, content ->
            if (failMetadata && file.name == "metadata.json") throw IOException("Metadata unavailable")
            writeLyricsFileAtomically(file, content)
        }
        assertTrue(cache.put("Artist", "Song", 180000, synced("Song", "Downloaded", 180000), "lrclib"))
        val key = cache.findExactEntries("Artist", "Song").single()["cacheKey"] as String
        val originalFile = File(directory, "$key.json")
        val original = originalFile.readText()
        val filesBefore = directory.listFiles()!!.map { it.name }.toSet()
        failMetadata = true

        assertFalse(cache.put("Artist", "Song", 0, synced("Song", "Replacement"), "custom", replaceExactMatches = true))
        assertEquals(original, originalFile.readText())
        assertEquals(filesBefore, directory.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun failedMetadataCommitRestoresAnExistingCustomEntry() {
        val directory = temporary.newFolder()
        var failMetadata = false
        val cache = LyricsCacheManager(directory) { file, content ->
            if (failMetadata && file.name == "metadata.json") throw IOException("Metadata unavailable")
            writeLyricsFileAtomically(file, content)
        }
        assertTrue(cache.put("Artist", "Song", 0, synced("Song", "Original"), "custom"))
        val filesBefore = directory.listFiles()!!.associate { it.name to it.readText() }
        failMetadata = true

        assertFalse(cache.put("Artist", "Song", 0, synced("Song", "Replacement"), "custom", replaceExactMatches = true))
        assertEquals(filesBefore, directory.listFiles()!!.associate { it.name to it.readText() })
        assertEquals("Original", (cache.get("Artist", "Song", 0) as LyricsData.Synced).lines.first().text)
    }

    @Test fun rejectedTitlesAreNotReportedAsSaved() {
        val cache = LyricsCacheManager(temporary.newFolder())
        assertFalse(cache.put("Artist", "!!!", 0, synced("!!!"), "custom"))
        assertTrue(cache.getAllEntries().isEmpty())
    }

    @Test fun syncedEditsKeepMillisecondsAndAreStoredInTimestampOrder() {
        val cache = LyricsCacheManager(temporary.newFolder())
        assertTrue(cache.put("Artist", "Song", 0, synced("Song"), "custom"))
        val key = cache.findExactEntries("Artist", "Song").single()["cacheKey"] as String
        assertTrue(cache.updateEntry(key, mapOf("lines" to listOf(
            mapOf("timestamp" to 23456L, "text" to "Later"),
            mapOf("timestamp" to 12345L, "text" to "Earlier")
        ))))
        val lines = cache.get("Artist", "Song", 0) as LyricsData.Synced
        assertEquals(listOf(12345L, 23456L), lines.lines.filter { it.timestamp >= 0 }.map { it.timestamp })
        assertEquals("Earlier", lines.lines.first().text)
    }

    @Test fun invalidSyncedEditsLeaveTheOriginalContentIntact() {
        val cache = LyricsCacheManager(temporary.newFolder())
        assertTrue(cache.put("Artist", "Song", 0, synced("Song"), "custom"))
        val key = cache.findExactEntries("Artist", "Song").single()["cacheKey"] as String
        for (lines in listOf(
            emptyList<Any>(),
            listOf(mapOf("timestamp" to -1, "text" to "Invalid")),
            listOf(mapOf("timestamp" to "wrong", "text" to "Invalid")),
            listOf(mapOf("text" to "Missing timestamp"))
        )) {
            assertFalse(cache.updateEntry(key, mapOf("lines" to lines)))
            val original = cache.get("Artist", "Song", 0) as LyricsData.Synced
            assertEquals(TimedLyricLine(12345, "Hello"), original.lines.first())
        }
    }
}
