package moe.shimmerfly.nekoplayer.presentation.components

import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

val LocalBottomFloatingCardDragModifier = compositionLocalOf<Modifier> { Modifier }

/**
 * A bottom-anchored modal card with seekable, predictive page navigation.
 *
 * [expansion] (0..1) grows the card into a full-screen surface: its gutters, corners and shadow
 * recede while its height rises from its measured size to the whole window. Content receives
 * that height as a fixed constraint each frame, so it expands in step with the container.
 * While collapsing, [restingHeight] (the content's natural height, in px) is the height the
 * card shrinks towards; without it the card returns to the height it had before expanding.
 * [swipeDismissEnabled] gates only the swipe-down gesture (header drag and overscroll); back and
 * the scrim still close the card. A full-screen card turns it off so scrolling to the top of its
 * content cannot be mistaken for a dismissal.
 */
@Composable
fun <Page : Any> BottomFloatingCard(
    page: Page,
    parentPage: Page?,
    pageOrder: (Page) -> Int,
    title: String,
    onNavigateBack: () -> Unit,
    onDismissRequest: () -> Unit,
    dismissEnabled: Boolean = true,
    swipeDismissEnabled: Boolean = true,
    expansion: () -> Float = { 0f },
    restingHeight: (() -> Int)? = null,
    modifier: Modifier = Modifier,
    content: @Composable (Page, onBack: () -> Unit, onClose: () -> Unit) -> Unit,
) {
    val motion = remember { MotionScheme.expressive() }
    val spatial = remember(motion) { motion.defaultSpatialSpec<Float>() }
    // The container itself uses the standard scheme's slow spatial spring (damping 0.9): a large
    // surface that overshoots its resting place reads as a jump at the end of the motion.
    val container = remember { MotionScheme.standard().slowSpatialSpec<Float>() }
    val reveal = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    val backProgress = remember { Animatable(0f) }
    var swipeY by remember { mutableFloatStateOf(0f) }
    var swipeJob by remember { mutableStateOf<Job?>(null) }
    var pastDismissThreshold by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val haptics = rememberMixHaptics()
    val focusManager = LocalFocusManager.current
    val pages = remember { SeekableTransitionState(page) }
    val transition = rememberTransition(pages, label = "floating_card_pages")
    val savedPages = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissAllowed = rememberUpdatedState(dismissEnabled)
    val swipeAllowed = rememberUpdatedState(dismissEnabled && swipeDismissEnabled)
    val dismissCallback = rememberUpdatedState(onDismissRequest)
    val navigateBack = rememberUpdatedState(onNavigateBack)
    val currentExpansion = rememberUpdatedState(expansion)
    val currentRestingHeight = rememberUpdatedState(restingHeight)
    var closing by remember { mutableStateOf(false) }
    var seekingBack by remember { mutableStateOf(false) }
    var swipeFromLeft by remember { mutableStateOf(true) }
    val collapsedHeight = remember { IntArray(1) }

    // The swipe-down offset is plain state, updated synchronously on every drag delta. (Routing each
    // delta through a coroutine read a stale value, dropped most of the distance, and let a late
    // snap cancel the release animation.) Only the release animates it.
    val dragFadeDistance = with(density) { 320.dp.toPx() }
    fun dragFade() = (1f - swipeY / dragFadeDistance).coerceIn(0f, 1f)

    fun dragBy(delta: Float) {
        if (!swipeAllowed.value || closing) return
        if (swipeY == 0f && delta > 0f) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
        swipeJob?.cancel()
        swipeJob = null
        swipeY = (swipeY + delta).coerceAtLeast(0f)
        // One tick as the drag passes the point where letting go dismisses the card.
        val pastThreshold = swipeY > with(density) { 96.dp.toPx() }
        if (pastThreshold != pastDismissThreshold) {
            pastDismissThreshold = pastThreshold
            if (pastThreshold) haptics.perform(MixHapticLevel.Threshold)
        }
    }

    fun settleSwipe(velocity: Float) {
        if (closing) return
        swipeJob?.cancel()
        swipeJob = scope.launch {
            val thresholdPx = with(density) { 96.dp.toPx() }
            val velocityThreshold = with(density) { 700.dp.toPx() }
            if (swipeAllowed.value && (swipeY > thresholdPx || velocity > velocityThreshold)) {
                closing = true
                haptics.perform(MixHapticLevel.Medium)
                launch { fade.animateTo(0f, motion.slowEffectsSpec()) }
                animate(swipeY, swipeY + with(density) { 350.dp.toPx() }, animationSpec = tween(200, easing = FastOutSlowInEasing)) { value, _ ->
                    swipeY = value
                }
                dismissCallback.value()
                // Normally this card leaves composition here and the coroutine ends. If the dismissal
                // was refused it stays, so bring it back instead of leaving it closed-but-visible.
                delay(400)
                closing = false
                launch { fade.animateTo(1f, motion.defaultEffectsSpec()) }
                animate(swipeY, 0f, animationSpec = motion.defaultSpatialSpec()) { value, _ -> swipeY = value }
            } else {
                pastDismissThreshold = false
                animate(swipeY, 0f, animationSpec = motion.defaultSpatialSpec()) { value, _ -> swipeY = value }
            }
        }
    }

    val headerDragModifier = Modifier.draggable(
        state = rememberDraggableState { delta -> dragBy(delta) },
        orientation = Orientation.Vertical,
        enabled = swipeAllowed.value && !closing,
        onDragStopped = { velocity -> settleSwipe(velocity) },
    )

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Not gated on swipeAllowed: a card that expands mid-drag must still be returned.
                if (!dismissAllowed.value || closing) return Offset.Zero
                // Scrolling back up first returns the card before the content scrolls.
                if (available.y < 0f && swipeY > 0f) {
                    val consumed = available.y.coerceAtLeast(-swipeY)
                    swipeY += consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!swipeAllowed.value || closing) return Offset.Zero
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    dragBy(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (swipeY > 0f && !closing) {
                    settleSwipe(available.y)
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (swipeY > 0f && !closing) {
                    settleSwipe(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    // A new dialog window receives its insets a frame after its first composition. The host
    // window already knows them, so they floor the dialog's own values: no gap snaps mid-reveal.
    val hostNav = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val hostTop = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }

    val close: () -> Unit = {
        if (dismissAllowed.value && !closing) {
            closing = true
            haptics.perform(MixHapticLevel.Medium)
            scope.launch {
                launch { fade.animateTo(0f, motion.slowEffectsSpec()) }
                reveal.animateTo(0f, container)
                dismissCallback.value()
            }
        }
    }
    val back: () -> Unit = {
        if (dismissAllowed.value && !closing) {
            if (parentPage != null) navigateBack.value() else close()
        }
    }
    LaunchedEffect(page) { if (!seekingBack) pages.animateTo(page) }

    MaterialTheme(motionScheme = motion) {
        Dialog(
            onDismissRequest = close,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            val view = LocalView.current
            val window = (view.parent as? DialogWindowProvider)?.window
            // Use this dialog's dispatcher, even when the caller supplied an activity owner.
            val dispatcherOwner = requireNotNull(view.findViewTreeOnBackPressedDispatcherOwner())
            LaunchedEffect(window) {
                window?.let {
                    WindowCompat.setDecorFitsSystemWindows(it, false)
                    @Suppress("DEPRECATION")
                    it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    it.setDimAmount(0f)
                    it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    // The card animates itself; a platform window animation would fight it.
                    it.setWindowAnimations(0)
                    @Suppress("DEPRECATION")
                    it.navigationBarColor = AndroidColor.TRANSPARENT
                    @Suppress("DEPRECATION")
                    it.statusBarColor = AndroidColor.TRANSPARENT
                    if (Build.VERSION.SDK_INT >= 29) it.isNavigationBarContrastEnforced = false
                }
            }
            LaunchedEffect(view) {
                // Start once the dialog window actually draws. Starting at composition spends the
                // opening of the spring on frames nobody sees, which reads as a jump.
                withTimeoutOrNull(250) { view.awaitPreDraw() }
                withFrameNanos { }
                if (closing) return@LaunchedEffect
                haptics.perform(MixHapticLevel.Medium)
                launch { fade.animateTo(1f, motion.defaultEffectsSpec()) }
                reveal.animateTo(1f, container)
            }
            val lightNavigation = neuralMixPalette().dark.not()
            LaunchedEffect(window, lightNavigation) {
                window?.let {
                    WindowCompat.getInsetsController(it, view).run {
                        isAppearanceLightNavigationBars = lightNavigation
                        isAppearanceLightStatusBars = lightNavigation
                    }
                }
            }
            CompositionLocalProvider(
                LocalOnBackPressedDispatcherOwner provides dispatcherOwner,
                LocalBottomFloatingCardDragModifier provides headerDragModifier,
            ) {
                PredictiveBackHandler {
                    val from = page
                    val destination = parentPage
                    try {
                        seekingBack = true
                        // A gesture during a page change seeks from the settled page, not mid-flight.
                        if (destination != null && (pages.currentState != from || pages.targetState != from)) pages.snapTo(from)
                        it.collect { event ->
                            if (dismissAllowed.value && !closing) {
                                swipeFromLeft = event.swipeEdge == 0
                                if (destination != null) pages.seekTo(event.progress, destination)
                                else backProgress.snapTo(event.progress)
                            }
                        }
                        if (dismissAllowed.value && !closing) {
                            if (destination != null) {
                                pages.animateTo(destination)
                                navigateBack.value()
                            } else close()
                        }
                    } catch (_: CancellationException) {
                        // Cancellation is a gesture reversal, not a navigation request.
                        withContext(NonCancellable) {
                            if (destination != null) pages.animateTo(from)
                            backProgress.animateTo(0f, spatial)
                        }
                    } finally {
                        seekingBack = false
                    }
                }
                val nav = maxOf(hostNav, with(density) { WindowInsets.navigationBars.getBottom(this).toDp() })
                val ime = with(density) { WindowInsets.ime.getBottom(this).toDp() }
                val top = maxOf(hostTop, with(density) { WindowInsets.safeDrawing.getTop(this).toDp() })
                val keyboardGap = if (ime > 0.dp) ime + 12.dp else 0.dp
                val bottomGap = maxOf(nav, keyboardGap, 8.dp)
                val horizontalGap = nav.coerceIn(8.dp, 16.dp)
                val scrim = MaterialTheme.colorScheme.scrim
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().drawBehind {
                        drawRect(scrim.copy(alpha = .32f * fade.value.coerceIn(0f, 1f) * (1f - backProgress.value * .35f) * dragFade()))
                    }.clickable(remember { MutableInteractionSource() }, indication = null, enabled = dismissEnabled && !closing, onClick = close)
                        .clearAndSetSemantics {})
                    Box(Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .layout { measurable, constraints ->
                            // Gutters recede with the expansion, read here so only layout reruns.
                            val keep = 1f - currentExpansion.value().coerceIn(0f, 1f)
                            val side = (horizontalGap.toPx() * keep).roundToInt()
                            val above = (top.toPx() * keep).roundToInt()
                            val below = (bottomGap.toPx() * keep).roundToInt()
                            val placeable = measurable.measure(constraints.offset(-2 * side, -(above + below)))
                            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(side, above) }
                        },
                        contentAlignment = Alignment.BottomCenter) {
                        Surface(
                            modifier = modifier.fillMaxWidth()
                                .nestedScroll(nestedScrollConnection)
                                .layout { measurable, constraints ->
                                val e = currentExpansion.value().coerceIn(0f, 1f)
                                if (e <= 0f) {
                                    val placeable = measurable.measure(constraints)
                                    collapsedHeight[0] = placeable.height
                                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                                } else {
                                    // Rise from the last resting height to the whole window, or settle
                                    // onto the content's natural height when collapsing.
                                    val base = currentRestingHeight.value?.invoke()?.takeIf { it > 0 } ?: collapsedHeight[0]
                                    val height = lerp(base.toFloat(), constraints.maxHeight.toFloat(), e)
                                        .roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
                                    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                                }
                            }.graphicsLayer {
                                val r = reveal.value
                                val p = backProgress.value
                                val keep = 1f - currentExpansion.value().coerceIn(0f, 1f)
                                transformOrigin = TransformOrigin(.5f, 1f)
                                scaleX = FloatingSurfaceMotion.scale(r, p)
                                scaleY = scaleX
                                // A short rise under a fade reads smoother than a full-height slide.
                                translationY = size.height * FloatingSurfaceMotion.verticalTravel(r, p) + swipeY
                                translationX = (if (swipeFromLeft) 1f else -1f) * FloatingSurfaceMotion.SwipeTravelDp.dp.toPx() * p
                                alpha = fade.value.coerceIn(0f, 1f) * dragFade()
                                // Corners and shadow recede in the draw phase as the card fills the screen.
                                shape = RoundedCornerShape(FloatingSurfaceMotion.CornerRadiusDp.dp.toPx() * keep)
                                clip = true
                                shadowElevation = 6.dp.toPx() * keep
                            }.semantics {
                                paneTitle = title
                                if (dismissEnabled && !closing) dismiss { close(); true }
                            },
                            shape = RectangleShape,
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            transition.AnimatedContent(
                                modifier = Modifier.fillMaxWidth(),
                                transitionSpec = {
                                    val returning = pageOrder(targetState) < pageOrder(initialState)
                                    val sign = if (returning) -1 else 1
                                    // A predictive back gesture scrubs this transition. A bouncy spring
                                    // would overshoot under the finger, so seeking uses a settled curve.
                                    val seeking = seekingBack
                                    val slide = if (seeking) tween<IntOffset>(SEEK_MILLIS, easing = FastOutSlowInEasing) else motion.defaultSpatialSpec()
                                    val fadeSpec = if (seeking) tween<Float>(SEEK_MILLIS, easing = FastOutSlowInEasing) else motion.defaultEffectsSpec()
                                    val sizeSpec = if (seeking) tween<IntSize>(SEEK_MILLIS, easing = FastOutSlowInEasing) else motion.defaultSpatialSpec()
                                    (slideInHorizontally(slide) { sign * it / 4 } + fadeIn(fadeSpec)) togetherWith
                                        (slideOutHorizontally(slide) { -sign * it / 4 } + fadeOut(fadeSpec)) using
                                        SizeTransform { _, _ -> sizeSpec }
                                },
                                contentAlignment = Alignment.BottomCenter,
                            ) { current -> savedPages.SaveableStateProvider(current) { content(current, back, close) } }
                        }
                    }
                }
            }
        }
    }
}

private const val SEEK_MILLIS = 350

/** Card geometry for the reveal, predictive back and swipe motion. */
private object FloatingSurfaceMotion {
    const val CornerRadiusDp = 26f
    const val SwipeTravelDp = 20f

    fun scale(reveal: Float, backProgress: Float): Float =
        .94f + .06f * reveal - .08f * backProgress

    fun verticalTravel(reveal: Float, backProgress: Float): Float =
        .4f * (1f - reveal) + .12f * backProgress
}

private suspend fun View.awaitPreDraw() = suspendCancellableCoroutine { continuation ->
    val listener = OneShotPreDrawListener.add(this) { if (continuation.isActive) continuation.resume(Unit) }
    continuation.invokeOnCancellation { listener.removeListener() }
}
