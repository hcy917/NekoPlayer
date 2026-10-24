package moe.shimmerfly.nekoplayer.presentation.components

import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewTreeObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the slider losing track of playback position: it must follow the current
 * value provider even when the caller swaps the state object behind it, which happens when a caller
 * keys its remember on the current song. Also covers the slider going idle once playback pauses.
 */
@RunWith(AndroidJUnit4::class)
class WavySliderExpressiveTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun reportedProgress(): Float {
        val node = composeRule.onNodeWithContentDescription(LABEL).fetchSemanticsNode()
        return node.config[SemanticsProperties.ProgressBarRangeInfo].current
    }

    @Test
    fun followsValueAfterBackingStateIsReplaced() {
        var stateKey by mutableStateOf(0)
        lateinit var latestState: MutableState<Float>

        composeRule.setContent {
            val position = remember(stateKey) { mutableStateOf(0.25f) }
            latestState = position
            WavySliderExpressive(
                value = { position.value },
                onValueChange = {},
                semanticsLabel = LABEL
            )
        }

        composeRule.waitForIdle()
        assertThat(reportedProgress()).isWithin(TOLERANCE).of(0.25f)

        composeRule.runOnUiThread { stateKey = 1 }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { latestState.value = 0.8f }
        composeRule.waitForIdle()

        assertThat(reportedProgress()).isWithin(TOLERANCE).of(0.8f)
    }

    @Test
    fun reportsNonFiniteValueAsZero() {
        composeRule.setContent {
            val position = remember { mutableFloatStateOf(Float.NaN) }
            WavySliderExpressive(
                value = { position.floatValue },
                onValueChange = {},
                semanticsLabel = LABEL
            )
        }

        composeRule.waitForIdle()
        assertThat(reportedProgress()).isWithin(TOLERANCE).of(0f)
    }

    /**
     * Pausing must leave the wave flat and the slider idle. When the indicator was fed the animated
     * amplitude it settled on a tiny residual wave after a pause, and its wave offset animation then
     * redrew the paused player on every frame.
     */
    @Test
    fun stopsRedrawingAfterPause() {
        var isPlaying by mutableStateOf(true)
        val draws = AtomicInteger()
        // While the test clock auto-advances it cancels infinite animations, which would hide the
        // wave offset animation that kept redrawing.
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            val view = LocalView.current
            DisposableEffect(view) {
                val listener = ViewTreeObserver.OnDrawListener { draws.incrementAndGet() }
                view.viewTreeObserver.addOnDrawListener(listener)
                onDispose { view.viewTreeObserver.removeOnDrawListener(listener) }
            }
            WavySliderExpressive(
                value = { 0.5f },
                onValueChange = {},
                isPlaying = isPlaying,
                semanticsLabel = LABEL
            )
        }
        advanceFrames(10)

        composeRule.runOnUiThread { isPlaying = false }
        // Long enough for the slider's amplitude spring and the indicator's own fade to settle.
        advanceFrames(150)
        Thread.sleep(SETTLE_WAIT_MS)

        draws.set(0)
        advanceFrames(20)
        assertThat(draws.get()).isEqualTo(0)
    }

    /**
     * A paused slider must also stop the indicator's wave offset animation. Even with the wave flat
     * and nothing redrawn, it wrote state on every frame and kept the main thread waking at the
     * display refresh rate.
     */
    @Test
    fun stopsAnimatingAfterPauseAndResumesOnPlay() {
        var isPlaying by mutableStateOf(true)
        composeRule.mainClock.autoAdvance = false
        setPlayingSlider { isPlaying }
        advanceFrames(40)
        assertThat(stateWritesDuring { advanceFrames(10) }).isGreaterThan(0)

        composeRule.runOnUiThread { isPlaying = false }
        advanceFrames(150)
        assertThat(stateWritesDuring { advanceFrames(20) }).isEqualTo(0)

        composeRule.runOnUiThread { isPlaying = true }
        advanceFrames(40)
        assertThat(waveOffsetWritesDuring { advanceFrames(10) }).isGreaterThan(0)
    }

    /**
     * Stopping the wave snaps its phase back to 0, so it must wait until the indicator has flattened
     * the wave or the paused slider visibly jumps. Pausing right after resuming is the slowest case:
     * the indicator finishes growing the wave before it starts flattening it.
     */
    @Test
    fun stopsAnimatingOnlyOnceTheWaveIsFlat() {
        var isPlaying by mutableStateOf(false)
        composeRule.mainClock.autoAdvance = false
        setPlayingSlider { isPlaying }
        advanceFrames(10)

        composeRule.runOnUiThread { isPlaying = true }
        advanceFrames(2)
        composeRule.runOnUiThread { isPlaying = false }

        var stopFrame = -1
        var previousImage = captureSlider()
        var lastImageBeforeStop: Bitmap? = null
        for (frame in 0 until 150) {
            val movedWave = waveOffsetWritesDuring { advanceFrames(1) } > 0
            val image = captureSlider()
            if (movedWave) {
                stopFrame = frame
                lastImageBeforeStop = previousImage
            }
            previousImage = image
        }

        // Stopped, with frames to spare to confirm it stayed stopped.
        assertThat(stopFrame).isAtLeast(1)
        assertThat(stopFrame).isAtMost(140)
        assertThat(visiblyChangedPixels(lastImageBeforeStop!!, previousImage)).isEqualTo(0)
    }

    private fun setPlayingSlider(isPlaying: () -> Boolean) {
        composeRule.setContent {
            WavySliderExpressive(
                value = { 0.5f },
                onValueChange = {},
                isPlaying = isPlaying(),
                semanticsLabel = LABEL
            )
        }
    }

    private fun captureSlider(): Bitmap =
        composeRule.onNodeWithContentDescription(LABEL).captureToImage().asAndroidBitmap()

    /**
     * Counts pixels that differ by more than antialiasing noise, such as the slider's gap easing
     * by a fraction of a pixel as its amplitude spring settles.
     */
    private fun visiblyChangedPixels(before: Bitmap, after: Bitmap): Int {
        assertThat(after.width).isEqualTo(before.width)
        assertThat(after.height).isEqualTo(before.height)
        var changed = 0
        for (y in 0 until before.height) {
            for (x in 0 until before.width) {
                val a = before.getPixel(x, y)
                val b = after.getPixel(x, y)
                val maxChannelDelta = maxOf(
                    abs(Color.alpha(a) - Color.alpha(b)),
                    abs(Color.red(a) - Color.red(b)),
                    abs(Color.green(a) - Color.green(b)),
                    abs(Color.blue(a) - Color.blue(b))
                )
                if (maxChannelDelta > PIXEL_NOISE) changed++
            }
        }
        return changed
    }

    private fun stateWritesDuring(block: () -> Unit): Int = countWrites({ true }, block)

    /**
     * Counts writes to the indicator's wave offset, the only float state the slider writes while
     * its value holds still. Its amplitude animations write object states instead.
     */
    private fun waveOffsetWritesDuring(block: () -> Unit): Int =
        countWrites({ it is MutableFloatState }, block)

    private fun countWrites(filter: (Any) -> Boolean, block: () -> Unit): Int {
        val writes = AtomicInteger()
        val handle = Snapshot.registerGlobalWriteObserver { if (filter(it)) writes.incrementAndGet() }
        try {
            block()
        } finally {
            handle.dispose()
        }
        return writes.get()
    }

    /**
     * Steps the test clock one frame at a time. Drawing runs on the real choreographer rather than
     * the test clock, so each step waits for that frame's invalidation to be drawn.
     */
    private fun advanceFrames(count: Int) {
        repeat(count) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            Thread.sleep(FRAME_DRAW_WAIT_MS)
        }
    }

    private companion object {
        const val LABEL = "Playback position"
        const val TOLERANCE = 0.02f
        const val FRAME_DRAW_WAIT_MS = 20L
        const val SETTLE_WAIT_MS = 100L
        const val PIXEL_NOISE = 24
    }
}
