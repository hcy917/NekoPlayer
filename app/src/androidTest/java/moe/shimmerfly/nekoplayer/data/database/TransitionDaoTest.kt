package moe.shimmerfly.nekoplayer.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.data.model.TransitionSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransitionDaoTest {

    private lateinit var database: NekoPlayerDatabase
    private lateinit var transitionDao: TransitionDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NekoPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        transitionDao = database.transitionDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun deletingTrackRulesKeepsTheDefaultRuleAndOtherPlaylists() = runTest {
        insertRules()

        transitionDao.deleteRulesForTrack(playlistId = "p", trackId = "a")

        val remaining = transitionDao.getAllRulesForPlaylist("p").first()
        assertThat(remaining).hasSize(1)
        assertThat(remaining.single().fromTrackId).isNull()
        assertThat(remaining.single().toTrackId).isNull()
        assertThat(remaining.single().settings.durationMs).isEqualTo(6000)
        assertThat(transitionDao.getAllRulesForPlaylist("other").first()).hasSize(1)
    }

    @Test
    fun deletingPlaylistRulesEmptiesOnlyThatPlaylist() = runTest {
        insertRules()

        transitionDao.deleteRulesForPlaylist("p")

        assertThat(transitionDao.getAllRulesForPlaylist("p").first()).isEmpty()
        val other = transitionDao.getAllRulesForPlaylist("other").first()
        assertThat(other).hasSize(1)
        assertThat(other.single().fromTrackId).isEqualTo("a")
        assertThat(other.single().toTrackId).isEqualTo("b")
    }

    private suspend fun insertRules() {
        val default = TransitionRuleEntity(
            playlistId = "p",
            fromTrackId = null,
            toTrackId = null,
            settings = TransitionSettings(durationMs = 6000),
        )
        transitionDao.setRule(default)
        transitionDao.setRule(default.copy(fromTrackId = "a", toTrackId = "b"))
        transitionDao.setRule(default.copy(playlistId = "other", fromTrackId = "a", toTrackId = "b"))
        assertThat(transitionDao.getAllRulesForPlaylist("p").first()).hasSize(2)
    }
}
