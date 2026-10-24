package moe.shimmerfly.nekoplayer.data.backup.module

import com.google.gson.GsonBuilder
import moe.shimmerfly.nekoplayer.data.database.AudioBookmarkDao
import moe.shimmerfly.nekoplayer.data.database.AudioBookmarkEntity
import moe.shimmerfly.nekoplayer.data.database.MusicDao
import moe.shimmerfly.nekoplayer.data.database.SongEntity
import moe.shimmerfly.nekoplayer.data.database.SongSummary
import moe.shimmerfly.nekoplayer.data.database.restoredBookmarkSongId
import moe.shimmerfly.nekoplayer.data.repository.AudioBookmarkRepositoryImpl
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AudioBookmarksModuleHandlerTest {
    private val gson = GsonBuilder().serializeNulls().create()
    private val sourceDao = mockk<MusicDao>()
    private val targetDao = mockk<MusicDao>()
    private val bookmarks = mockk<AudioBookmarkDao>(relaxed = true)

    private fun summary(id: Long, title: String) = SongSummary(id, title, "Artist", "Album", 200_000)

    // A non-local content uri keeps the stored artwork uri as-is through toSong().
    private fun song(id: Long, title: String, art: String) = SongEntity(
        id = id, title = title, artistName = "Artist", artistId = 1, albumName = "Album", albumId = 1,
        contentUriString = "jellyfin://$id", albumArtUriString = art, duration = 200_000, genre = null,
        filePath = "", parentDirectoryPath = ""
    )

    private fun bookmark(id: Long, songId: String, songTitle: String, title: String) = AudioBookmarkEntity(
        id = id, songId = songId, songTitle = songTitle, artistName = "Artist",
        albumArtUri = "content://media/external/audio/albumart/$songId", title = title, timestampMs = 30_000, createdTime = 1
    )

    private fun restoreOnTarget(payload: String): List<AudioBookmarkEntity> {
        val restored = slot<List<AudioBookmarkEntity>>()
        coEvery { bookmarks.replaceAll(capture(restored)) } just Runs
        runTest { AudioBookmarksModuleHandler(bookmarks, BackupSongResolver(targetDao), gson).restore(payload) }
        return restored.captured
    }

    @Test
    fun `bookmarks follow their songs to another device's ids and take its artwork`() = runTest {
        coEvery { sourceDao.getAllLocalSongSummaries() } returns listOf(summary(1, "One"), summary(2, "Two"))
        coEvery { bookmarks.getAllBookmarksOnce() } returns listOf(
            bookmark(10, "1", "One", "Drop"),
            bookmark(11, "2", "Two", "Chorus"),
        )
        val payload = AudioBookmarksModuleHandler(bookmarks, BackupSongResolver(sourceDao), gson).export()

        // New device: same songs under new ids, and id 1 now names another song.
        coEvery { targetDao.getAllLocalSongSummaries() } returns
            listOf(summary(1, "Other"), summary(41, "One"), summary(42, "Two"))
        coEvery { targetDao.getSongsByIdsListSimple(any()) } returns
            listOf(song(41, "One", "art://41"), song(42, "Two", "art://42"))

        val restored = restoreOnTarget(payload).associateBy { it.title }
        assertEquals("41", restored.getValue("Drop").songId)
        assertEquals("art://41", restored.getValue("Drop").albumArtUri)
        assertEquals("42", restored.getValue("Chorus").songId)
        assertEquals("art://42", restored.getValue("Chorus").albumArtUri)
    }

    @Test
    fun `a bookmark whose song isn't here yet is kept under a restored id rather than its old one`() {
        // Id 1 names another song here, and the bookmark's own may be on storage not scanned yet.
        val legacy = """[{"id":10,"songId":"1","songTitle":"Gone","artistName":"Artist","albumArtUri":"art://old","title":"Drop","timestampMs":5000,"createdTime":1}]"""
        coEvery { targetDao.getAllLocalSongSummaries() } returns listOf(summary(1, "Other"))
        coEvery { targetDao.getSongsByIdsListSimple(any()) } returns emptyList()

        val restored = restoreOnTarget(legacy).single()
        assertEquals(restoredBookmarkSongId("1"), restored.songId)
        assertEquals("Gone", restored.songTitle)
        assertEquals("Drop", restored.title)
        assertEquals(5000L, restored.timestampMs)
        assertNull(restored.albumArtUri)
    }

    @Test
    fun `bookmarks restored before the first scan reach their songs once it runs`() = runTest {
        coEvery { sourceDao.getAllLocalSongSummaries() } returns
            listOf(summary(130, "Chapter 31"), summary(131, "Chapter 32"))
        coEvery { bookmarks.getAllBookmarksOnce() } returns listOf(
            bookmark(10, "130", "Chapter 31", "Drop"),
            bookmark(11, "131", "Chapter 32", "Chorus"),
        )
        val payload = AudioBookmarksModuleHandler(bookmarks, BackupSongResolver(sourceDao), gson).export()

        // New device, not scanned yet.
        coEvery { targetDao.getAllLocalSongSummaries() } returns emptyList()
        coEvery { targetDao.getSongsByIdsListSimple(any()) } returns emptyList()
        val restored = restoreOnTarget(payload)
        assertEquals(setOf(restoredBookmarkSongId("130"), restoredBookmarkSongId("131")), restored.map { it.songId }.toSet())

        // The scan gives id 130 to another chapter by the same artist, and 131 to nothing.
        val scanned = listOf(song(130, "Chapter 11", "art://130"), song(150, "Chapter 31", "art://150"), song(151, "Chapter 32", "art://151"))
        coEvery { targetDao.getAllLocalSongSummaries() } returns scanned.map { SongSummary(it.id, it.title, "Artist", "Album", 200_000) }
        coEvery { targetDao.getSongsByIdsListSimple(any()) } answers {
            val ids = firstArg<List<Long>>()
            scanned.filter { it.id in ids }
        }
        coEvery { bookmarks.getAllBookmarksOnce() } returns restored
        val relinked = slot<List<AudioBookmarkEntity>>()
        coEvery { bookmarks.insertAll(capture(relinked)) } just Runs
        AudioBookmarkRepositoryImpl(bookmarks, BackupSongResolver(targetDao)).relinkOrphanedBookmarks()

        val byTitle = relinked.captured.associateBy { it.title }
        assertEquals("150", byTitle.getValue("Drop").songId)
        assertEquals("art://150", byTitle.getValue("Drop").albumArtUri)
        assertEquals("151", byTitle.getValue("Chorus").songId)
    }

    @Test
    fun `a bookmark restored again before it was matched is matched on the backup's id`() {
        val payload = """[
            {"id":10,"songId":"restored:1","songTitle":"One","artistName":"Artist","title":"Drop","timestampMs":5000,"createdTime":1},
            {"id":11,"songId":"restored:2","songTitle":"Gone","artistName":"Artist","title":"Chorus","timestampMs":5000,"createdTime":1}
        ]"""
        coEvery { targetDao.getAllLocalSongSummaries() } returns listOf(summary(1, "One"), summary(2, "Other"))
        coEvery { targetDao.getSongsByIdsListSimple(any()) } returns emptyList()

        val restored = restoreOnTarget(payload).associate { it.title to it.songId }
        assertEquals(mapOf("Drop" to "1", "Chorus" to restoredBookmarkSongId("2")), restored)
    }

    @Test
    fun `a legacy payload is matched on the stored title and artist`() {
        val legacy = """[{"id":10,"songId":"7","songTitle":"One","artistName":"Artist","title":"Drop","timestampMs":5000,"createdTime":1}]"""
        coEvery { targetDao.getAllLocalSongSummaries() } returns listOf(summary(41, "One"))
        coEvery { targetDao.getSongsByIdsListSimple(any()) } returns emptyList()

        assertEquals("41", restoreOnTarget(legacy).single().songId)
    }

    @Test
    fun `an unmatched cloud song keeps its id, which is the same on every device`() {
        // A synced cloud song's id, and a server's own song id.
        val legacy = """[
            {"id":10,"songId":"-55","songTitle":"Remote","artistName":"Artist","title":"Drop","timestampMs":5000,"createdTime":1},
            {"id":11,"songId":"navidrome_abc","songTitle":"Streamed","artistName":"Artist","title":"Chorus","timestampMs":5000,"createdTime":1}
        ]"""
        coEvery { targetDao.getAllLocalSongSummaries() } returns emptyList()
        coEvery { targetDao.getSongsByIdsListSimple(any()) } returns emptyList()

        val restored = restoreOnTarget(legacy).associateBy { it.title }
        assertEquals("-55", restored.getValue("Drop").songId)
        assertNull(restored.getValue("Drop").albumArtUri)
        assertEquals("navidrome_abc", restored.getValue("Chorus").songId)
    }

    @Test
    fun `malformed entries are skipped or repaired instead of failing the restore`() {
        val payload = """[
            {"id":1,"songTitle":"No id","title":"x","timestampMs":1},
            {"id":2,"songId":"  ","title":"blank id","timestampMs":1},
            {"id":3,"songId":"41","timestampMs":-20},
            "not an object"
        ]"""
        coEvery { targetDao.getAllLocalSongSummaries() } returns listOf(summary(41, "One"))
        coEvery { targetDao.getSongsByIdsListSimple(any()) } returns emptyList()

        val restored = restoreOnTarget(payload).single()
        assertEquals("41", restored.songId)
        assertEquals("", restored.title)
        assertEquals("", restored.songTitle)
        assertEquals(0L, restored.timestampMs)
    }
}
