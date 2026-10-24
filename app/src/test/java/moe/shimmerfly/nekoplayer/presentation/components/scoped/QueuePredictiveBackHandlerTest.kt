package moe.shimmerfly.nekoplayer.presentation.components.scoped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueuePredictiveBackHandlerTest {

    private val resetSpec = tween<Float>(durationMillis = RESET_DURATION_MS, easing = LinearEasing)

    @Test
    fun committedBack_holdsTheReleaseProgressUntilTheQueueIsHidden() = runFrameTest {
        val progress = Animatable(RELEASE_PROGRESS)
        val sheetHidden = CompletableDeferred<Unit>()
        val close = launch { sheetHidden.await() }

        val settle = launch { settleQueueBackProgress(progress, resetSpec, committedClose = close) }
        repeat(60) {
            advanceTimeBy(FRAME_MS)
            runCurrent()
            assertEquals(RELEASE_PROGRESS, progress.value)
        }
        assertFalse(settle.isCompleted)

        sheetHidden.complete(Unit)
        runCurrent()

        // Cleared in the same step the sheet is hidden, without running any frames.
        assertEquals(0f, progress.value)
        assertTrue(settle.isCompleted)
    }

    @Test
    fun committedBack_onlyLetsTheQueueCoverageFallWhileTheSheetSlidesOut() = runFrameTest {
        val progress = Animatable(RELEASE_PROGRESS)
        val offset = Animatable(0f)
        var isQueueShown = true
        // Mirrors QueueSheetController.animateTo(false) from a fully open sheet.
        val close = launch {
            offset.animateTo(
                targetValue = HIDDEN_OFFSET_PX,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
            )
            isQueueShown = false
        }
        launch {
            settleQueueBackProgress(
                progress = progress,
                resetAnimationSpec = tween(RESET_DURATION_MS),
                committedClose = close
            )
        }

        fun coverage() = computeQueueVisualOpenFraction(
            isQueueShown = isQueueShown,
            queueSheetOffsetPx = offset.value,
            queueHiddenOffsetPx = HIDDEN_OFFSET_PX,
            predictiveBackProgress = progress.value
        )

        var previous = coverage()
        while (!close.isCompleted) {
            advanceTimeBy(FRAME_MS)
            runCurrent()
            val current = coverage()
            assertTrue(
                current <= previous,
                "Queue coverage rose from $previous to $current while the sheet was closing"
            )
            previous = current
        }
        advanceUntilIdle()

        assertEquals(0f, coverage())
        assertEquals(0f, progress.value)
    }

    @Test
    fun committedBack_interruptedByAReopen_easesTheProgressBackToRest() = runFrameTest {
        val progress = Animatable(RELEASE_PROGRESS)
        val offset = Animatable(0f)
        val close = launch {
            offset.animateTo(HIDDEN_OFFSET_PX, tween(durationMillis = 300, easing = LinearEasing))
        }
        launch { settleQueueBackProgress(progress, resetSpec, committedClose = close) }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(RELEASE_PROGRESS, progress.value)

        // A reopen (or a drag stopping the sheet) takes the offset over mid-slide.
        launch { offset.animateTo(0f, tween(durationMillis = 300, easing = LinearEasing)) }
        runCurrent()
        assertTrue(close.isCancelled)

        advanceTimeBy(RESET_DURATION_MS / 2L)
        runCurrent()
        assertTrue(progress.value > 0f && progress.value < RELEASE_PROGRESS)

        advanceUntilIdle()
        assertEquals(0f, progress.value)
    }

    @Test
    fun cancelledBack_easesTheProgressBackToRest() = runFrameTest {
        val progress = Animatable(RELEASE_PROGRESS)

        val settle = launch { settleQueueBackProgress(progress, resetSpec, committedClose = null) }
        advanceTimeBy(RESET_DURATION_MS / 2L)
        runCurrent()
        assertTrue(progress.value > 0f && progress.value < RELEASE_PROGRESS)

        advanceUntilIdle()
        assertEquals(0f, progress.value)
        assertTrue(settle.isCompleted)
    }

    /** Runs [body] with a frame clock driven by virtual time, so Animatable.animateTo can run. */
    private fun runFrameTest(body: suspend TestScope.() -> Unit): TestResult {
        val scheduler = TestCoroutineScheduler()
        return runTest(StandardTestDispatcher(scheduler) + VirtualTimeFrameClock(scheduler)) {
            body()
        }
    }

    private class VirtualTimeFrameClock(
        private val scheduler: TestCoroutineScheduler
    ) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(FRAME_MS)
            return onFrame(scheduler.currentTime * 1_000_000L)
        }
    }

    private companion object {
        const val RELEASE_PROGRESS = 0.9f
        const val HIDDEN_OFFSET_PX = 1200f
        const val RESET_DURATION_MS = 255
        const val FRAME_MS = 16L
    }
}
