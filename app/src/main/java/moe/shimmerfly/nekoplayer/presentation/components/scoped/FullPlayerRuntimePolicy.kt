package moe.shimmerfly.nekoplayer.presentation.components.scoped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import moe.shimmerfly.nekoplayer.presentation.viewmodel.PlayerSheetState

internal data class FullPlayerRuntimePolicy(
    val allowRealtimeUpdates: Boolean
)

/**
 * Gates high-frequency UI updates (progress bar sampling, animations) behind
 * conditions that only flip at expansion thresholds — not on every frame.
 *
 * [expansionFraction] is read inside [derivedStateOf], producing a Boolean that
 * changes only when crossing the 0.985 / 0.95 thresholds. This avoids per-frame
 * recomposition of the caller during gestures.
 *
 * [currentSheetState] and [isQueueOccludingPlayer] are used as `remember` keys because they
 * only flip on sheet state transitions or when the queue crosses its occlusion threshold. The
 * queue's open fraction itself follows the finger, so it must not be passed in here.
 */
@Composable
internal fun rememberFullPlayerRuntimePolicy(
    currentSheetState: PlayerSheetState,
    expansionFraction: Animatable<Float, AnimationVector1D>,
    isQueueOccludingPlayer: Boolean
): FullPlayerRuntimePolicy {
    val allowRealtimeUpdates by remember(currentSheetState, isQueueOccludingPlayer) {
        derivedStateOf {
            val ef = expansionFraction.value
            val alpha = (ef - 0.25f).coerceIn(0f, 0.75f) / 0.75f

            currentSheetState == PlayerSheetState.EXPANDED &&
                ef >= 0.985f &&
                alpha >= 0.95f &&
                !isQueueOccludingPlayer
        }
    }

    return FullPlayerRuntimePolicy(
        allowRealtimeUpdates = allowRealtimeUpdates
    )
}
