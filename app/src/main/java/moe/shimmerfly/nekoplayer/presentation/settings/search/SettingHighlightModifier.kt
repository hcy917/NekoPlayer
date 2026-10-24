package moe.shimmerfly.nekoplayer.presentation.settings.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** The setting a search result pointed at, for the destination it opened. */
class SettingsHighlightTarget internal constructor(
    val itemKey: String,
    private val onConsumed: () -> Unit,
) {
    /**
     * Set once the row was brought into view. A plain field, so setting it never recomposes
     * or restarts the row that is pulsing.
     */
    internal var isConsumed = false
        private set

    internal fun consume() {
        if (isConsumed) return
        isConsumed = true
        onConsumed()
    }
}

val LocalSettingsHighlightTarget = compositionLocalOf<SettingsHighlightTarget?> { null }

/**
 * Exposes the search target for [highlightKey] to the settings rows below it.
 * The highlight plays once per destination: it is not replayed when coming back from a
 * sub-screen or after a configuration change, even though the nav argument is still there.
 */
@Composable
fun ProvideSettingsHighlight(highlightKey: String?, content: @Composable () -> Unit) {
    var consumed by rememberSaveable(highlightKey) { mutableStateOf(false) }
    // Keyed on the key alone: consuming only records it in saved state, so a pulse that is
    // already playing keeps its target, while a restored or re-entered destination starts
    // without one.
    val target = remember(highlightKey) {
        if (highlightKey.isNullOrBlank() || consumed) {
            null
        } else {
            SettingsHighlightTarget(itemKey = highlightKey, onConsumed = { consumed = true })
        }
    }
    CompositionLocalProvider(LocalSettingsHighlightTarget provides target, content = content)
}

/** Matches the 10dp clip the shared settings rows use. */
val DefaultSettingHighlightShape: Shape = RoundedCornerShape(10.dp)

/**
 * Tags a row with its registry [itemKey]. When it is the search target, the row scrolls
 * itself into view and pulses once. Only fires under [ProvideSettingsHighlight].
 */
fun Modifier.settingHighlight(
    itemKey: String,
    highlightKey: String?,
    shape: Shape = DefaultSettingHighlightShape,
    onHighlightFinished: (() -> Unit)? = null
): Modifier = composed {
    val target = LocalSettingsHighlightTarget.current
    if (target == null || highlightKey != itemKey || target.itemKey != itemKey) {
        return@composed this
    }
    // A row composed again after it was brought into view, such as a lazy item scrolled back
    // in, stays plain. Read once, so consuming does not cancel the pulse that is playing.
    val alreadyConsumed = remember(target) { target.isConsumed }
    if (alreadyConsumed) {
        return@composed this
    }

    val highlightAlpha = remember { Animatable(0f) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    // Only the effect reads the size, so writing it never recomposes the row.
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val currentOnFinished by rememberUpdatedState(onHighlightFinished)

    LaunchedEffect(target) {
        // Let the destination finish its enter transition and first layout pass.
        delay(250)
        // Ask for room above and below the row so it lands clear of the collapsing top
        // bar and the mini player instead of flush against the viewport edges.
        val topRoom = with(density) { 200.dp.toPx() }
        val bottomRoom = with(density) { 140.dp.toPx() }
        try {
            bringIntoViewRequester.bringIntoView(
                Rect(0f, -topRoom, size.width.toFloat(), size.height + bottomRoom)
            )
        } catch (e: CancellationException) {
            // A user scroll cancels the request but not this effect, so still pulse then;
            // rethrow only when the effect itself was cancelled.
            currentCoroutineContext().ensureActive()
        }
        // The user has seen the row now. Record it before the pulse, so leaving mid-pulse
        // (opening the row, rotating) does not replay it when the destination comes back.
        target.consume()
        delay(150)

        // Pulse 1: initial bright glow burst
        highlightAlpha.animateTo(0.60f, animationSpec = tween(250, easing = FastOutSlowInEasing))
        highlightAlpha.animateTo(0.20f, animationSpec = tween(250, easing = FastOutSlowInEasing))
        // Pulse 2: sustained glow holding focus, then fading out gracefully
        highlightAlpha.animateTo(0.50f, animationSpec = tween(250, easing = FastOutSlowInEasing))
        highlightAlpha.animateTo(0f, animationSpec = tween(2000, easing = FastOutSlowInEasing))

        currentOnFinished?.invoke()
    }

    this
        .bringIntoViewRequester(bringIntoViewRequester)
        .onSizeChanged { size = it }
        .drawWithContent {
            drawContent()
            // Read in the draw phase so the pulse redraws without recomposing the row.
            val alpha = highlightAlpha.value
            if (alpha > 0f && !this.size.isEmpty()) {
                drawOutline(
                    outline = shape.createOutline(this.size, layoutDirection, this),
                    color = primaryColor.copy(alpha = alpha)
                )
            }
        }
}

/** Overload for positional argument compatibility when passing callback without shape. */
fun Modifier.settingHighlight(
    itemKey: String,
    highlightKey: String?,
    onHighlightFinished: (() -> Unit)?
): Modifier = settingHighlight(
    itemKey = itemKey,
    highlightKey = highlightKey,
    shape = DefaultSettingHighlightShape,
    onHighlightFinished = onHighlightFinished
)
