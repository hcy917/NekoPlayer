package moe.shimmerfly.nekoplayer.presentation.components.scoped

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Back handling for the queue sheet.
 *
 * On Android 14+ the live gesture progress is written to [progress], which the queue sheet and its
 * scrim read in the draw phase only. Committing runs [closeQueue] and holds the progress at its
 * release value until the sheet is hidden; cancelling eases it back to 0 with the sheet still open
 * (see [settleQueueBackProgress]). A back gesture made while a committed close is still sliding
 * the sheet out is taken but changes nothing. Older versions get a plain [BackHandler].
 *
 * The close and the resets run in [scope], which must outlive this handler (the sheet's scope): the
 * handler can leave composition mid-gesture or mid-reset, and a scope of its own would be cancelled
 * with it, leaving [progress] stuck at a partial value for the next time the queue opens.
 */
@Composable
internal fun QueuePredictiveBackHandler(
    enabled: Boolean,
    progress: Animatable<Float, AnimationVector1D>,
    animationDurationMs: Int,
    scope: CoroutineScope,
    closeQueue: suspend () -> Unit
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val committedClose = remember { CommittedQueueClose() }
        PredictiveBackHandler(enabled = enabled) { progressFlow ->
            if (committedClose.job?.isActive == true) {
                // The queue is already closing. Keep the gesture from reaching the screen behind,
                // but leave the held progress alone so the sheet doesn't re-inflate on its way out.
                progressFlow.collect { }
                return@PredictiveBackHandler
            }
            try {
                progressFlow.collect { backEvent ->
                    progress.snapTo(backEvent.progress)
                }
                val close = scope.launch { closeQueue() }
                committedClose.job = close
                scope.launch {
                    settleQueueBackProgress(
                        progress = progress,
                        resetAnimationSpec = tween(animationDurationMs),
                        committedClose = close
                    )
                }
            } catch (_: CancellationException) {
                scope.launch {
                    settleQueueBackProgress(
                        progress = progress,
                        resetAnimationSpec = tween(animationDurationMs),
                        committedClose = null
                    )
                }
            }
        }
    } else {
        BackHandler(enabled = enabled) {
            scope.launch { closeQueue() }
        }
    }
}

/**
 * Brings the queue's predictive back [progress] back to 0 once a back gesture has ended.
 *
 * [committedClose] is the close a committed gesture started, or null if the gesture was
 * cancelled. A committed gesture keeps its release progress until that close finishes. The scrim
 * and the full player's scale follow `dragFraction * (1 - progress)`, and easing the progress to 0
 * alongside the longer slide made that product climb again, so the scrim darkened and the player
 * shrank after the back was already committed. Once the sheet is hidden nothing shows the reset,
 * so it snaps. When a drag or a reopen interrupts the close, or the gesture was cancelled, the
 * sheet stays on screen and eases back to its resting size with [resetAnimationSpec].
 */
internal suspend fun settleQueueBackProgress(
    progress: Animatable<Float, AnimationVector1D>,
    resetAnimationSpec: AnimationSpec<Float>,
    committedClose: Job?
) {
    if (committedClose != null) {
        committedClose.join()
        if (!committedClose.isCancelled) {
            progress.snapTo(0f)
            return
        }
    }
    progress.animateTo(targetValue = 0f, animationSpec = resetAnimationSpec)
}

/** The close started by the last committed gesture. Only the gesture reads it, so it isn't state. */
private class CommittedQueueClose {
    var job: Job? = null
}
