package moe.shimmerfly.nekoplayer.data.repository

import moe.shimmerfly.nekoplayer.data.backup.module.BackupSongResolver
import moe.shimmerfly.nekoplayer.data.database.AudioBookmarkDao
import moe.shimmerfly.nekoplayer.data.database.AudioBookmarkEntity
import moe.shimmerfly.nekoplayer.data.database.MusicDao
import moe.shimmerfly.nekoplayer.data.database.SongEntity
import moe.shimmerfly.nekoplayer.data.database.SongSummary
import moe.shimmerfly.nekoplayer.data.database.restoredBookmarkSongId
import moe.shimmerfly.nekoplayer.data.database.serializeArtistRefs
import moe.shimmerfly.nekoplayer.data.model.ArtistRef
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudioBookmarkRepositoryImplTest {
    private val musicDao = mockk<MusicDao>()
    private val bookmarkDao = mockk<AudioBookmarkDao>(relaxed = true)
    private val repository = AudioBookmarkRepositoryImpl(bookmarkDao, BackupSongResolver(musicDao))

    // A non-local content uri keeps the stored artwork uri as-is through toSong().
    private fun song(id: Long, title: String, artist: String = "Artist", artists: List<String> = emptyList()) =
        SongEntity(
            id = id, title = title, artistName = artist, artistId = 1, albumName = "Album", albumId = 1,
            contentUriString = "jellyfin://$id", albumArtUriString = "art://$id", duration = 200_000, genre = null,
            filePath = "", parentDirectoryPath = "",
            artistsJson = artists.takeIf { it.isNotEmpty() }?.let { names ->
                serializeArtistRefs(names.mapIndexed { index, name -> ArtistRef(index.toLong(), name, index == 0) })
            }
        )

    private fun bookmark(id: Long, songId: String, songTitle: String, artistName: String = "Artist") =
        AudioBookmarkEntity(
            id = id, songId = songId, songTitle = songTitle, artistName = artistName,
            albumArtUri = null, title = "Cue $id", timestampMs = 30_000, createdTime = 1
        )

    /** A library holding [songs], served by id and as local summaries with their raw artist column. */
    private fun libraryOf(vararg songs: SongEntity) {
        val byId = songs.associateBy { it.id }
        coEvery { musicDao.getSongsByIdsListSimple(any()) } answers {
            firstArg<List<Long>>().mapNotNull { byId[it] }
        }
        coEvery { musicDao.getAllLocalSongSummaries() } returns songs.map {
            SongSummary(it.id, it.title, it.artistName, it.albumName, it.duration)
        }
    }

    private fun bookmarksAre(vararg bookmarks: AudioBookmarkEntity) {
        coEvery { bookmarkDao.getAllBookmarksOnce() } returns bookmarks.toList()
    }

    private fun captureWrites() = slot<List<AudioBookmarkEntity>>().also { written ->
        coEvery { bookmarkDao.insertAll(capture(written)) } just Runs
    }

    @Test
    fun `a restored bookmark moves to its song once the library has it, not to the song given its old id`() = runTest {
        // Restored before the scan, which then gave its old id to another chapter of the same book.
        bookmarksAre(bookmark(10, restoredBookmarkSongId("130"), "Chapter 31", artistName = "Author"))
        libraryOf(song(130, "Chapter 11", artist = "Author"), song(131, "Chapter 31", artist = "Author"))
        val written = captureWrites()

        repository.relinkOrphanedBookmarks()

        val relinked = written.captured.single()
        assertEquals(10L, relinked.id)
        assertEquals("131", relinked.songId)
        assertEquals("art://131", relinked.albumArtUri)
    }

    @Test
    fun `a restored bookmark goes back to the song holding the backup's id when it has the stored title and artist`() = runTest {
        // A restore onto the same device before it was scanned: the id still names the song, even
        // though another one has the same title and artist.
        bookmarksAre(bookmark(10, restoredBookmarkSongId("41"), "Intro"))
        libraryOf(song(41, "Intro"), song(42, "Intro"))
        val written = captureWrites()

        repository.relinkOrphanedBookmarks()

        assertEquals("41", written.captured.single().songId)
    }

    @Test
    fun `a restored bookmark whose song is still missing keeps its restored id`() = runTest {
        bookmarksAre(bookmark(10, restoredBookmarkSongId("1"), "One"))
        libraryOf(song(1, "Other", artist = "Someone"))

        repository.relinkOrphanedBookmarks()

        coVerify(exactly = 0) { bookmarkDao.insertAll(any()) }
    }

    @Test
    fun `a restored bookmark matched to its song stays on it after the song is retagged outside the app`() = runTest {
        bookmarksAre(bookmark(10, restoredBookmarkSongId("1"), "Lecture 3", artistName = "<unknown>"))
        libraryOf(song(97, "Lecture 3", artist = "<unknown>"))
        val matched = captureWrites()
        repository.relinkOrphanedBookmarks()
        assertEquals("97", matched.captured.single().songId)

        // Tagged in another app: MediaStore keeps the id. Another file still has the old tags.
        bookmarksAre(*matched.captured.toTypedArray())
        libraryOf(song(97, "Lecture 3 - Thermodynamics", artist = "Prof. Smith"), song(98, "Lecture 3", artist = "<unknown>"))
        captureWrites()
        repository.relinkOrphanedBookmarks()

        coVerify(exactly = 1) { bookmarkDao.insertAll(any()) }
    }

    @Test
    fun `a bookmark on a song retagged outside the app stays on it`() = runTest {
        // MediaStore keeps a file's id when it is retagged. Another file still has the old tags.
        bookmarksAre(bookmark(10, "41", "Track 01", artistName = "<unknown>"))
        libraryOf(song(41, "Chapter 1", artist = "Author"), song(42, "Track 01", artist = "<unknown>"))

        repository.relinkOrphanedBookmarks()

        coVerify(exactly = 0) { bookmarkDao.insertAll(any()) }
        coVerify(exactly = 0) { musicDao.getAllLocalSongSummaries() }
    }

    @Test
    fun `a multi-artist bookmark is matched on its display artist`() = runTest {
        // Bookmarks store the display artist; the library's artist column keeps the tag as written.
        bookmarksAre(bookmark(10, restoredBookmarkSongId("1"), "Song", artistName = "A, B"))
        libraryOf(
            song(1, "Other", artist = "Someone"),
            song(41, "Song", artist = "A feat. B", artists = listOf("A", "B")),
            song(42, "Song", artist = "C")
        )
        val written = captureWrites()

        repository.relinkOrphanedBookmarks()

        assertEquals("41", written.captured.single().songId)
    }

    @Test
    fun `a bookmark whose song was re-indexed under a new id follows it`() = runTest {
        bookmarksAre(bookmark(10, "7", "One"), bookmark(11, "7", "One"))
        libraryOf(song(41, "One"))
        val written = captureWrites()

        repository.relinkOrphanedBookmarks()

        assertEquals(mapOf(10L to "41", 11L to "41"), written.captured.associate { it.id to it.songId })
    }

    @Test
    fun `a bookmark whose song is gone and unmatched is kept as it is`() = runTest {
        // A cloud song not synced yet, or a file that may come back.
        bookmarksAre(bookmark(10, "7", "One"))
        libraryOf(song(41, "Two"))

        repository.relinkOrphanedBookmarks()

        coVerify(exactly = 0) { bookmarkDao.insertAll(any()) }
    }
}
