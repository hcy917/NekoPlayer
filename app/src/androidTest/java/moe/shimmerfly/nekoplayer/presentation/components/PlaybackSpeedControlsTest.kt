package moe.shimmerfly.nekoplayer.presentation.components

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.R
import moe.shimmerfly.nekoplayer.utils.PlaybackSpeedUtils
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The speed controls must report exact hundredths (1.15f, never 1.1499999f), commit a slider
 * gesture once, keep the newest pick while older preference writes echo back, and only accept
 * typed speeds inside the supported range.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackSpeedControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val reportedSpeeds = mutableListOf<Float>()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val locale
        get() = context.resources.configuration.locales[0]

    private fun valueLabel(speed: Float) =
        context.getString(R.string.playback_speed_value, PlaybackSpeedUtils.formatFixed(speed, locale))

    private fun presetLabel(speed: Float) =
        context.getString(R.string.playback_speed_value, PlaybackSpeedUtils.formatCompact(speed, locale))

    private fun showControls(initialSpeed: Float = 1f) {
        composeRule.setContent {
            // Stands in for the preference round trip: the reported value comes back as speed.
            var speed by remember { mutableFloatStateOf(initialSpeed) }
            PlaybackSpeedControls(
                speed = speed,
                onSpeedChange = {
                    reportedSpeeds += it
                    speed = it
                }
            )
        }
    }

    /** Reported values that have not come back as speed yet, oldest first. */
    private val undeliveredEchoes = ArrayDeque<Float>()

    private val storedSpeed = mutableFloatStateOf(1f)

    /**
     * Stands in for a slow preference write: a reported value only comes back as speed when
     * [deliverNextEcho] is called, in the order the values were reported.
     */
    private fun showControlsWithDelayedEchoes(initialSpeed: Float = 1f) {
        storedSpeed.floatValue = initialSpeed
        composeRule.setContent {
            PlaybackSpeedControls(
                speed = storedSpeed.floatValue,
                onSpeedChange = {
                    reportedSpeeds += it
                    undeliveredEchoes.addLast(it)
                }
            )
        }
    }

    private fun deliverNextEcho() {
        composeRule.runOnIdle { storedSpeed.floatValue = undeliveredEchoes.removeFirst() }
    }

    private fun increaseButton() =
        composeRule.onNodeWithContentDescription(context.getString(R.string.playback_speed_increase))

    private fun decreaseButton() =
        composeRule.onNodeWithContentDescription(context.getString(R.string.playback_speed_decrease))

    private fun okButton() =
        composeRule.onNode(hasText(context.getString(R.string.ok)) and hasClickAction())

    @Test
    fun tappingIncreaseThreeTimesReportsExactlyOnePointOneFive() {
        showControls()

        repeat(3) { increaseButton().performClick() }

        composeRule.runOnIdle {
            assertThat(reportedSpeeds.last()).isEqualTo(1.15f)
        }
        composeRule.onNodeWithText(valueLabel(1.15f)).assertIsDisplayed()
        composeRule.onNodeWithTag(PLAYBACK_SPEED_SLIDER_TEST_TAG).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(
                    R.string.playback_speed_state_description,
                    PlaybackSpeedUtils.formatCompact(1.15f, locale)
                )
            )
        )
    }

    @Test
    fun tapsHandledBeforeTheNextFrameAreAllCounted() {
        showControlsWithDelayedEchoes()
        val increase = increaseButton().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!

        // Input events that arrive within one frame are handled together, before the controls
        // recompose; running the clicks in one main-thread task reproduces that.
        composeRule.runOnUiThread { repeat(3) { increase() } }

        composeRule.runOnIdle {
            assertThat(reportedSpeeds).containsExactly(1.05f, 1.1f, 1.15f).inOrder()
        }
        composeRule.onNodeWithText(valueLabel(1.15f)).assertIsDisplayed()
    }

    @Test
    fun anOlderEchoArrivingLateDoesNotRewindQuickTaps() {
        showControlsWithDelayedEchoes()

        repeat(2) { increaseButton().performClick() }
        // The 1.05 write lands while 1.10 is still on its way.
        deliverNextEcho()
        composeRule.onNodeWithText(valueLabel(1.1f)).assertIsDisplayed()
        increaseButton().performClick()
        repeat(2) { deliverNextEcho() }

        composeRule.runOnIdle {
            assertThat(reportedSpeeds).containsExactly(1.05f, 1.1f, 1.15f).inOrder()
        }
        composeRule.onNodeWithText(valueLabel(1.15f)).assertIsDisplayed()
    }

    @Test
    fun tappingBackToTheStoredSpeedBeforeTheEchoStillReportsIt() {
        showControlsWithDelayedEchoes()

        increaseButton().performClick()
        decreaseButton().performClick()

        composeRule.runOnIdle {
            assertThat(reportedSpeeds).containsExactly(1.05f, 1f).inOrder()
        }
        repeat(2) { deliverNextEcho() }
        composeRule.onNodeWithText(valueLabel(1f)).assertIsDisplayed()
    }

    @Test
    fun draggingBackToTheStoredSpeedBeforeTheEchoStillReportsIt() {
        showControlsWithDelayedEchoes()

        increaseButton().performClick()
        composeRule.onNodeWithTag(PLAYBACK_SPEED_SLIDER_TEST_TAG)
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(1f) }

        composeRule.runOnIdle {
            assertThat(reportedSpeeds).containsExactly(1.05f, 1f).inOrder()
        }
    }

    @Test
    fun aSpeedChangedElsewhereIsShownOnceThePickHasBeenStored() {
        showControlsWithDelayedEchoes()

        increaseButton().performClick()
        deliverNextEcho()
        // For example from the speed sheet in the queue.
        composeRule.runOnIdle { storedSpeed.floatValue = 2f }

        composeRule.onNodeWithText(valueLabel(2f)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertThat(reportedSpeeds).containsExactly(1.05f)
        }
    }

    @Test
    fun sliderProgressSnapsToTheNearestStopAndCommitsOnce() {
        showControls()

        composeRule.onNodeWithTag(PLAYBACK_SPEED_SLIDER_TEST_TAG)
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(1.1473f) }

        composeRule.runOnIdle {
            assertThat(reportedSpeeds).containsExactly(1.15f)
        }
    }

    @Test
    fun tappingAPresetSelectsIt() {
        showControls()
        val preset = composeRule.onNode(hasText(presetLabel(1.5f)) and hasClickAction())

        preset.performClick()

        composeRule.runOnIdle {
            assertThat(reportedSpeeds.last()).isEqualTo(1.5f)
        }
        preset.assertIsSelected()
    }

    @Test
    fun decreaseIsDisabledAtTheSlowestSpeed() {
        showControls(initialSpeed = 0.25f)

        decreaseButton().assertIsNotEnabled()
        increaseButton().assertIsEnabled()
    }

    @Test
    fun increaseIsDisabledAtTheFastestSpeed() {
        showControls(initialSpeed = 3f)

        increaseButton().assertIsNotEnabled()
        decreaseButton().assertIsEnabled()
    }

    @Test
    fun typedSpeedWithDecimalCommaIsReportedExactly() {
        showControls()

        composeRule.onNode(hasText(valueLabel(1f)) and hasClickAction()).performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("1,08")
        okButton().assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertThat(reportedSpeeds.last()).isEqualTo(1.08f)
        }
        composeRule.onNodeWithText(valueLabel(1.08f)).assertIsDisplayed()
    }

    @Test
    fun typedSpeedOutsideTheRangeShowsAnErrorAndCannotBeConfirmed() {
        showControls()

        composeRule.onNode(hasText(valueLabel(1f)) and hasClickAction()).performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("3.5")

        composeRule.onNodeWithText(
            context.getString(
                R.string.playback_speed_custom_error,
                PlaybackSpeedUtils.formatCompact(PlaybackSpeedUtils.MIN_SPEED, locale),
                PlaybackSpeedUtils.formatCompact(PlaybackSpeedUtils.MAX_SPEED, locale)
            )
        ).assertIsDisplayed()
        okButton().assertIsNotEnabled()
        composeRule.runOnIdle {
            assertThat(reportedSpeeds).isEmpty()
        }
    }
}
