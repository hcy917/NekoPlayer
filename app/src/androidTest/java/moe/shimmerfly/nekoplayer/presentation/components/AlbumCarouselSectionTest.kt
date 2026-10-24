package moe.shimmerfly.nekoplayer.presentation.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.data.model.Song
import kotlinx.collections.immutable.toImmutableList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The full player's album carousel must only change the song for a scroll the user started: a
 * swipe or an accessibility page action. The scrolls it runs itself for next/previous, including
 * ones a skip burst cuts short on an intermediate page, must never report a selection back to the
 * player.
 */
@RunWith(AndroidJUnit4::class)
class AlbumCarouselSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val queue = List(5) { index ->
        Song.emptySong().copy(
            id = "carousel-song-$index",
            title = titleOf(index),
            albumId = 1L,
            contentUriString = "content://media/external/audio/media/$index"
        )
    }.toImmutableList()

    private var currentIndex by mutableIntStateOf(0)
    private var requestedIndex by mutableStateOf<Int?>(null)
    private val selections = mutableListOf<Int>()

    private fun setCarousel(playerFollowsSelection: Boolean = false) {
        composeRule.setContent {
            MaterialTheme {
                AlbumCarouselSection(
                    currentSong = queue[currentIndex],
                    queue = queue,
                    expansionFraction = 1f,
                    currentMediaItemIndex = currentIndex,
                    requestedScrollIndex = requestedIndex,
                    onSongSelected = { _, index ->
                        selections += index
                        if (playerFollowsSelection) currentIndex = index
                    },
                    modifier = Modifier
                        .size(320.dp)
                        .testTag(CAROUSEL_TAG)
                )
            }
        }
    }

    /** The pager node, the only one with page actions. */
    private fun carouselPager(): SemanticsNodeInteraction =
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.PageRight))

    private fun carouselScrollOffset(): Float =
        carouselPager().fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange]
            .value()

    /** Only the focused page's album art is clickable, so it names the page the pager is on. */
    private fun assertFocusedSong(index: Int) {
        composeRule.onNode(hasClickAction() and isEnabled())
            .assert(hasContentDescription(titleOf(index), substring = true))
    }

    private fun focusedSongIndex(): Int? {
        val descriptions = composeRule.onAllNodes(hasClickAction() and isEnabled())
            .fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        return queue.indices.firstOrNull { index ->
            descriptions.any { it.contains(titleOf(index)) }
        }
    }

    /** Steps the clock a frame at a time until the scroll in flight reports [index] as its page. */
    private fun advanceFramesUntilFocusedSong(index: Int) {
        repeat(MAX_SCROLL_FRAMES) {
            composeRule.mainClock.advanceTimeByFrame()
            if (focusedSongIndex() == index) return
        }
        throw AssertionError("The carousel never passed ${titleOf(index)}")
    }

    /** The player reaches the skipped-to song and the pending carousel index is cleared. */
    private fun catchUpPlayer(index: Int) {
        composeRule.runOnUiThread {
            currentIndex = index
            requestedIndex = null
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    }

    @Test
    fun skipBurstEndsOnTheLastSkippedSongWithoutSelectingAnyOther() {
        composeRule.mainClock.autoAdvance = false
        setCarousel()
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)
        assertFocusedSong(0)

        // Quick taps on Next: the scroll towards song 2 is retargeted to song 3 while it is
        // passing song 1, which is neither the playing song nor a target.
        composeRule.runOnUiThread { requestedIndex = 2 }
        composeRule.waitForIdle()
        advanceFramesUntilFocusedSong(1)
        composeRule.runOnUiThread { requestedIndex = 3 }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)
        assertFocusedSong(3)

        catchUpPlayer(3)

        assertThat(selections).isEmpty()
        assertFocusedSong(3)
    }

    @Test
    fun skipReversedMidScrollComesToRestOnItsTargetWithoutSelectingIt() {
        composeRule.mainClock.autoAdvance = false
        setCarousel()
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)

        // Next, Next, then Previous while the scroll towards song 2 is passing song 1. The pager
        // already reports song 1 as its page there, but it is still between pages.
        composeRule.runOnUiThread { requestedIndex = 2 }
        composeRule.waitForIdle()
        advanceFramesUntilFocusedSong(1)
        composeRule.runOnUiThread { requestedIndex = 1 }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)

        assertThat(selections).isEmpty()
        assertFocusedSong(1)
        val restingOffset = carouselScrollOffset()

        // The carousel already rests on song 1, so the player catching up moves nothing.
        catchUpPlayer(1)

        assertThat(selections).isEmpty()
        assertFocusedSong(1)
        assertThat(carouselScrollOffset()).isWithin(0.5f).of(restingOffset)
    }

    @Test
    fun swipeSelectsTheNextSongExactlyOnce() {
        setCarousel(playerFollowsSelection = true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertThat(selections).containsExactly(1)
        assertFocusedSong(1)
    }

    @Test
    fun accessibilityPageActionSelectsTheNextSongExactlyOnce() {
        setCarousel(playerFollowsSelection = true)
        composeRule.waitForIdle()

        // TalkBack, Switch Access and Voice Access page the carousel without any drag.
        carouselPager().performSemanticsAction(SemanticsActions.PageRight)
        composeRule.waitForIdle()

        assertThat(selections).containsExactly(1)
        assertFocusedSong(1)
    }

    @Test
    fun programmaticScrollToAnotherSongDoesNotSelectIt() {
        setCarousel()
        composeRule.waitForIdle()

        composeRule.runOnUiThread { requestedIndex = 2 }
        composeRule.waitForIdle()
        assertFocusedSong(2)

        // The skip never reached the player, so the carousel returns to the playing song.
        composeRule.runOnUiThread { requestedIndex = null }
        composeRule.waitForIdle()

        assertThat(selections).isEmpty()
        assertFocusedSong(0)
    }

    private companion object {
        const val CAROUSEL_TAG = "album_carousel"
        const val MAX_SCROLL_FRAMES = 30
        const val SETTLE_MS = 2_000L

        fun titleOf(index: Int) = "Carousel Track $index"
    }
}
