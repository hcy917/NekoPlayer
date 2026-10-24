package moe.shimmerfly.nekoplayer.data.preferences

import moe.shimmerfly.nekoplayer.data.database.LocalPlaylistDao
import moe.shimmerfly.nekoplayer.data.database.PlaylistEntity
import moe.shimmerfly.nekoplayer.data.database.PlaylistSongEntity
import moe.shimmerfly.nekoplayer.data.database.PlaylistWithSongsEntity
import moe.shimmerfly.nekoplayer.data.database.TransitionDao
import moe.shimmerfly.nekoplayer.data.model.Playlist
import moe.shimmerfly.nekoplayer.data.model.SMART_PLAYLIST_SOURCE_LEGACY
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaylistPreferencesRepositoryTest {

    private val playlistDao = mockk<LocalPlaylistDao>(relaxed = true)
    private val transitionDao = mockk<TransitionDao>(relaxed = true)
    private val userPreferences = mockk<UserPreferencesRepository>(relaxed = true)

    private fun repository(
        storedIds: List<String>,
        source: String = "LOCAL",
    ): PlaylistPreferencesRepository {
        val rows = storedIds.mapIndexed { index, songId ->
            PlaylistSongEntity(playlistId = PLAYLIST_ID, songId = songId, sortOrder = index)
        }
        coEvery { playlistDao.getPlaylistCount() } returns 1
        every { playlistDao.observePlaylistsWithSongs() } returns flowOf(
            listOf(
                PlaylistWithSongsEntity(
                    playlist = PlaylistEntity(id = PLAYLIST_ID, name = "Mix", source = source),
                    songs = rows,
                )
            )
        )
        every { playlistDao.observePlaylistSongs(PLAYLIST_ID) } returns flowOf(rows)
        return PlaylistPreferencesRepository(playlistDao, userPreferences, transitionDao)
    }

    @Test
    fun `a reordered permutation is saved`() = runTest {
        val repository = repository(storedIds = listOf("a", "b", "c"))

        val saved = repository.reorderSongsInPlaylist(PLAYLIST_ID, listOf("c", "a", "b"))

        assertEquals(listOf("c", "a", "b"), saved)
        coVerify(exactly = 1) { playlistDao.replacePlaylistSongs(PLAYLIST_ID, listOf("c", "a", "b")) }
    }

    @Test
    fun `songs missing from the visible order keep their stored positions`() = runTest {
        val repository = repository(storedIds = listOf("a", "hidden", "b"))

        val saved = repository.reorderSongsInPlaylist(PLAYLIST_ID, listOf("b", "a"))

        assertEquals(listOf("b", "hidden", "a"), saved)
        coVerify(exactly = 1) { playlistDao.replacePlaylistSongs(PLAYLIST_ID, listOf("b", "hidden", "a")) }
    }

    @Test
    fun `an order that drops one copy of a shown song is refused without writing`() = runTest {
        val repository = repository(storedIds = listOf("a", "b", "a"))

        val saved = repository.reorderSongsInPlaylist(PLAYLIST_ID, listOf("b", "a"))

        assertNull(saved)
        coVerify(exactly = 0) { playlistDao.replacePlaylistSongs(any(), any()) }
    }

    @Test
    fun `an order with a song that is not in the playlist is refused without writing`() = runTest {
        val repository = repository(storedIds = listOf("a", "b"))

        val saved = repository.reorderSongsInPlaylist(PLAYLIST_ID, listOf("b", "a", "foreign"))

        assertNull(saved)
        coVerify(exactly = 0) { playlistDao.replacePlaylistSongs(any(), any()) }
    }

    @Test
    fun `duplicate songs can swap places with other songs`() = runTest {
        val repository = repository(storedIds = listOf("a", "a", "b"))

        val saved = repository.reorderSongsInPlaylist(PLAYLIST_ID, listOf("a", "b", "a"))

        assertEquals(listOf("a", "b", "a"), saved)
        coVerify(exactly = 1) { playlistDao.replacePlaylistSongs(PLAYLIST_ID, listOf("a", "b", "a")) }
    }

    @Test
    fun `an order that changes how often a song appears is refused`() = runTest {
        val repository = repository(storedIds = listOf("a", "a", "b"))

        val saved = repository.reorderSongsInPlaylist(PLAYLIST_ID, listOf("a", "b", "b"))

        assertNull(saved)
        coVerify(exactly = 0) { playlistDao.replacePlaylistSongs(any(), any()) }
    }

    @Test
    fun `smart playlists cannot be reordered`() = runTest {
        val repository = repository(storedIds = listOf("a", "b"), source = SMART_PLAYLIST_SOURCE_LEGACY)

        val saved = repository.reorderSongsInPlaylist(PLAYLIST_ID, listOf("b", "a"))

        assertNull(saved)
        coVerify(exactly = 0) { playlistDao.replacePlaylistSongs(any(), any()) }
    }

    @Test
    fun `deleting a playlist deletes its transition rules`() = runTest {
        val repository = repository(storedIds = listOf("a", "b"))

        repository.deletePlaylist(PLAYLIST_ID)

        coVerify(exactly = 1) { playlistDao.deletePlaylist(PLAYLIST_ID) }
        coVerify(exactly = 1) { transitionDao.deleteRulesForPlaylist(PLAYLIST_ID) }
    }

    @Test
    fun `removing every copy of a track deletes only that track's rules`() = runTest {
        val repository = repository(storedIds = listOf("a", "b", "b", "c"))

        repository.updatePlaylist(playlist(songIds = listOf("a", "b")))

        coVerify(exactly = 1) { transitionDao.deleteRulesForTrack(PLAYLIST_ID, "c") }
        coVerify(exactly = 1) { transitionDao.deleteRulesForTrack(any(), any()) }
    }

    @Test
    fun `removing one copy of a duplicated track keeps its rules`() = runTest {
        val repository = repository(storedIds = listOf("a", "b", "b", "c"))

        repository.updatePlaylist(playlist(songIds = listOf("a", "b", "c")))

        coVerify(exactly = 0) { transitionDao.deleteRulesForTrack(any(), any()) }
    }

    @Test
    fun `replacing a playlist's songs leaves its name and date alone`() = runTest {
        val repository = repository(storedIds = listOf("a"))
        coEvery { playlistDao.replaceSongsOfExistingPlaylist(PLAYLIST_ID, listOf("a", "b")) } returns true

        assertTrue(repository.replacePlaylistSongs(PLAYLIST_ID, listOf("a", "b")))

        coVerify(exactly = 1) { playlistDao.replaceSongsOfExistingPlaylist(PLAYLIST_ID, listOf("a", "b")) }
        coVerify(exactly = 0) { playlistDao.upsertPlaylist(any()) }
    }

    private fun playlist(songIds: List<String>) = Playlist(id = PLAYLIST_ID, name = "Mix", songIds = songIds)

    private companion object {
        const val PLAYLIST_ID = "p"
    }
}
