package moe.shimmerfly.nekoplayer.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPlaylistDaoTest {

    private lateinit var database: NekoPlayerDatabase
    private lateinit var playlistDao: LocalPlaylistDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NekoPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        playlistDao = database.localPlaylistDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun repeatedTracksRemainAtEachPlaylistPosition() = runTest {
        playlistDao.replacePlaylistSongs(
            playlistId = "playlist-1",
            songIds = listOf("intro", "chorus", "chorus", "outro"),
        )

        val storedIds = playlistDao.observePlaylistSongs("playlist-1")
            .first()
            .map(PlaylistSongEntity::songId)

        assertThat(storedIds)
            .containsExactly("intro", "chorus", "chorus", "outro")
            .inOrder()
    }

    @Test
    fun replacingSongsOfAnExistingPlaylistLeavesItsRowAlone() = runTest {
        val playlist = PlaylistEntity(
            id = "playlist-1",
            name = "Road trip",
            createdAt = 1_000L,
            lastModified = 2_000L,
        )
        playlistDao.upsertPlaylist(playlist)
        playlistDao.replacePlaylistSongs("playlist-1", listOf("intro"))

        val replaced = playlistDao.replaceSongsOfExistingPlaylist("playlist-1", listOf("intro", "outro"))

        assertThat(replaced).isTrue()
        val stored = playlistDao.observePlaylistWithSongs("playlist-1").first()
        assertThat(stored?.playlist).isEqualTo(playlist)
        assertThat(stored?.songs?.sortedBy { it.sortOrder }?.map(PlaylistSongEntity::songId))
            .containsExactly("intro", "outro")
            .inOrder()
    }

    @Test
    fun replacingSongsOfADeletedPlaylistWritesNothing() = runTest {
        val replaced = playlistDao.replaceSongsOfExistingPlaylist("deleted", listOf("intro"))

        assertThat(replaced).isFalse()
        assertThat(playlistDao.observePlaylistSongs("deleted").first()).isEmpty()
    }
}
