package moe.shimmerfly.nekoplayer.presentation.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.R
import moe.shimmerfly.nekoplayer.data.model.Song
import moe.shimmerfly.nekoplayer.presentation.viewmodel.GenreDetailListItem
import moe.shimmerfly.nekoplayer.presentation.viewmodel.StablePlayerState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Genre songs take part in the shared multi-selection: a long press starts or extends the
 * selection, and once selecting, a tap toggles the song instead of playing it.
 */
@RunWith(AndroidJUnit4::class)
class GenreSongItemWrapperSelectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val song = Song.emptySong().copy(
        id = "genre-song-1",
        title = TITLE,
        artist = "Test Artist",
        album = "Test Album"
    )

    private val item = GenreDetailListItem.SongItem(
        key = "song_${song.id}",
        song = song,
        isFirstInAlbum = true,
        isLastInAlbum = true,
        isLastAlbumInSection = true,
        useArtistStyle = false
    )

    private var songClicks = 0
    private var longPresses = 0

    private fun setItem(isSelectionMode: () -> Boolean) {
        composeRule.setContent {
            MaterialTheme {
                val selecting = isSelectionMode()
                GenreSongItemWrapper(
                    item = item,
                    stablePlayerState = StablePlayerState(),
                    onSongClick = { songClicks++ },
                    onMoreOptionsClick = {},
                    isSelectionMode = selecting,
                    isSelected = selecting,
                    selectionIndex = if (selecting) 1 else null,
                    onLongPress = { longPresses++ }
                )
            }
        }
    }

    @Test
    fun longPressReportsLongPressWithoutPlaying() {
        setItem { false }

        composeRule.onNodeWithText(TITLE).performTouchInput { longClick() }

        composeRule.runOnIdle {
            assertThat(longPresses).isEqualTo(1)
            assertThat(songClicks).isEqualTo(0)
        }
    }

    @Test
    fun tapOutsideSelectionModePlaysTheSong() {
        setItem { false }

        composeRule.onNodeWithText(TITLE).performClick()

        composeRule.runOnIdle {
            assertThat(songClicks).isEqualTo(1)
            assertThat(longPresses).isEqualTo(0)
        }
    }

    @Test
    fun tapInSelectionModeTogglesSelectionInsteadOfPlaying() {
        setItem { true }

        composeRule.onNodeWithText(TITLE).performClick()

        composeRule.runOnIdle {
            assertThat(longPresses).isEqualTo(1)
            assertThat(songClicks).isEqualTo(0)
        }
    }

    @Test
    fun moreOptionsButtonIsHiddenWhileSelecting() {
        var selecting by mutableStateOf(false)
        setItem { selecting }
        val moreOptions = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.presentation_batch_g_list_cd_more_for_title, TITLE)

        composeRule.onNodeWithContentDescription(moreOptions).assertExists()

        composeRule.runOnIdle { selecting = true }

        composeRule.onNodeWithContentDescription(moreOptions).assertDoesNotExist()
    }

    private companion object {
        const val TITLE = "Genre Test Song"
    }
}
