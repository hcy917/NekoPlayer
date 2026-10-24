package moe.shimmerfly.nekoplayer.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import moe.shimmerfly.nekoplayer.presentation.utils.LocalAppHapticsConfig

/**
 * How much an action matters, from a light tick to the confirmation of the thing the sheet exists
 * for. Each level is a distinct platform haptic, so the weight of the feedback follows the weight
 * of the action.
 */
internal enum class MixHapticLevel(val type: HapticFeedbackType) {
    /** Sending a request, playing or applying the mix, saving it. */
    Strong(HapticFeedbackType.Confirm),
    /** Choosing a suggestion, opening refinements, the sheet opening or closing. */
    Medium(HapticFeedbackType.ContextClick),
    /** Secondary buttons: back, edit. */
    Light(HapticFeedbackType.VirtualKey),
    /** Picking an option among several. */
    Tick(HapticFeedbackType.SegmentTick),
    /** A drag crossing the point where releasing will dismiss. */
    Threshold(HapticFeedbackType.GestureThresholdActivate),
}

@Stable
internal class MixHaptics(private val feedback: HapticFeedback, private val enabled: Boolean) {
    fun perform(level: MixHapticLevel) {
        if (enabled) feedback.performHapticFeedback(level.type)
    }
}

/**
 * The floating card lives in its own window, where the platform feedback replaces the app's scoped
 * one, so the app-wide haptics switch is honoured here explicitly.
 */
@Composable
internal fun rememberMixHaptics(): MixHaptics {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalAppHapticsConfig.current.enabled
    return remember(feedback, enabled) { MixHaptics(feedback, enabled) }
}
