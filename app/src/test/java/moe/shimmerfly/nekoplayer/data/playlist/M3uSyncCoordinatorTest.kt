package moe.shimmerfly.nekoplayer.data.playlist

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import moe.shimmerfly.nekoplayer.data.model.Playlist
import moe.shimmerfly.nekoplayer.data.preferences.M3uSyncConfig
import moe.shimmerfly.nekoplayer.data.preferences.M3uSyncPreferences
import moe.shimmerfly.nekoplayer.data.preferences.PlaylistPreferencesRepository
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class M3uSyncCoordinatorTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val preferences = mockk<M3uSyncPreferences>()
    private val playlistRepository = mockk<PlaylistPreferencesRepository>()

    private val road = Playlist("road", "Road trip", listOf("41"), createdAt = EDIT_TIME, lastModified = EDIT_TIME)

    /** Like Room's, it emits again on every write to the playlist tables, changed or not. */
    private val playlists = MutableSharedFlow<List<Playlist>>(replay = 1)

    private fun TestScope.startCoordinator() {
        every { preferences.configFlow } returns flowOf(M3uSyncConfig(treeUri = "content://tree/music"))
        every { playlistRepository.userPlaylistsFlow } returns playlists
        playlists.tryEmit(listOf(road))
        M3uSyncCoordinator(workManager, preferences, playlistRepository, backgroundScope).start()
        advanceTimeBy(DEBOUNCE_WAIT_MS)
        runCurrent()
        clearMocks(workManager, answers = false)
    }

    private fun verifyExportsRequested(times: Int) = verify(exactly = times) {
        workManager.enqueueUniqueWork(
            "automatic_m3u_sync_immediate",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            any<OneTimeWorkRequest>(),
        )
    }

    @Test
    fun `a change to a playlist's songs alone requests an export`() = runTest {
        startCoordinator()

        // Songs filled in after a restore keep the playlist's date.
        playlists.emit(listOf(road.copy(songIds = listOf("41", "42"))))
        advanceTimeBy(DEBOUNCE_WAIT_MS)
        runCurrent()

        verifyExportsRequested(times = 1)
    }

    @Test
    fun `playlists emitted again unchanged don't request an export`() = runTest {
        startCoordinator()

        playlists.emit(listOf(road.copy()))
        advanceTimeBy(DEBOUNCE_WAIT_MS)
        runCurrent()

        verifyExportsRequested(times = 0)
    }

    private companion object {
        const val EDIT_TIME = 1_700_000_000_000L
        /** Longer than the coordinator's playlist-change debounce. */
        const val DEBOUNCE_WAIT_MS = 2_000L
    }
}
