package moe.shimmerfly.nekoplayer.presentation.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import moe.shimmerfly.nekoplayer.R
import kotlin.math.hypot
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Size the system splash draws `windowSplashScreenAnimatedIcon` at when it has no icon
 * background (the 108dp adaptive canvas scaled to 288dp, masked to a 192dp circle). Drawing
 * [R.drawable.splash_icon] at this size puts the first frame exactly over the system one.
 */
private val SystemSplashIconSize = 288.dp

/** How far the mark shrinks to sit inside its container. */
private const val GlyphSettledScale = 0.6f

/** Container diameter relative to the settled mark. */
private const val ContainerToGlyph = 1.85f
private const val HaloToContainer = 1.24f

private const val SystemSplashWaitTimeoutMs = 700L
private const val NoteBeatDelayMs = 170L
private const val EmblemBeatDelayMs = 500L
private const val RevealDelayMs = 700L
private const val RevealDurationMs = 480

// M3 emphasized-accelerate: the reveal starts gently under the emblem, then rushes out.
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * Animated launch screen, drawn over the app right after the system splash hands over.
 *
 * It starts on the exact frame the system splash shows (the monochrome app mark on a flat
 * background, both from `splash_colors`), waits for the system view to leave, then:
 * 1. blends those colours into the app theme while an expressive container blooms in behind
 *    the mark on an overshooting spring and the mark settles into it;
 * 2. the music note beats, then the whole emblem beats, while the container morphs through
 *    Material 3 Expressive shapes (Soft burst → Cookie 9 → Cookie 12) and a counter-rotating
 *    halo trails it;
 * 3. the container's shape becomes a window: a hole of the same, now circular, shape grows
 *    from the centre and reveals the app as the emblem swells and fades.
 *
 * Everything is one Canvas in an offscreen layer (the reveal clears pixels), so the whole
 * sequence is a single draw pass per frame with no recomposition.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NekoPlayerSplash(
    systemSplashGone: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glyph = rememberSplashGlyph()
    val scheme = MaterialTheme.colorScheme
    val startBackground = colorResource(R.color.splash_background)
    val startGlyph = colorResource(R.color.splash_glyph)
    val currentOnFinished by rememberUpdatedState(onFinished)

    val containerMorphs = remember {
        morphChain(
            MaterialShapes.SoftBurst,
            MaterialShapes.Cookie9Sided,
            MaterialShapes.Cookie12Sided,
            MaterialShapes.Circle
        )
    }
    val haloMorphs = remember {
        morphChain(
            MaterialShapes.Cookie12Sided,
            MaterialShapes.SoftBurst,
            MaterialShapes.Cookie9Sided,
            MaterialShapes.Circle
        )
    }
    val scratch = remember { android.graphics.Path() }

    val blend = remember { Animatable(0f) }
    val enter = remember { Animatable(0f) }
    val settle = remember { Animatable(0f) }
    val spin = remember { Animatable(0f) }
    val morph = remember { Animatable(0f) }
    val noteBeat = remember { Animatable(0f) }
    val emblemBeat = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }

    // Hold the static first frame until the system splash has faded out on top of it;
    // animating underneath it shows two marks for the length of that fade. The timeout
    // covers launches where the system never calls the exit listener.
    val currentSystemSplashGone by rememberUpdatedState(systemSplashGone)
    LaunchedEffect(Unit) {
        withTimeoutOrNull(SystemSplashWaitTimeoutMs) {
            snapshotFlow { currentSystemSplashGone }.first { it }
        }
        coroutineScope {
            launch { blend.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
            launch { enter.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f)) }
            launch { settle.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 320f)) }
            launch {
                spin.animateTo(
                    1f,
                    tween((RevealDelayMs + RevealDurationMs).toInt(), easing = LinearOutSlowInEasing)
                )
            }
            launch {
                morph.animateTo(2f, tween(RevealDelayMs.toInt(), easing = FastOutSlowInEasing))
                morph.animateTo(3f, tween(RevealDurationMs, easing = FastOutSlowInEasing))
            }
            launch {
                delay(NoteBeatDelayMs)
                noteBeat.animateTo(1f, tween(130, easing = FastOutSlowInEasing))
                noteBeat.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 380f))
            }
            launch {
                delay(EmblemBeatDelayMs)
                emblemBeat.animateTo(1f, tween(110, easing = FastOutSlowInEasing))
                emblemBeat.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 420f))
            }
            delay(RevealDelayMs)
            reveal.animateTo(1f, tween(RevealDurationMs, easing = EmphasizedAccelerate))
        }
        currentOnFinished()
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clearAndSetSemantics { }
            // The app underneath is already live; keep stray taps from reaching it blind.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        val t = blend.value
        val r = reveal.value
        val background = lerp(startBackground, scheme.surface, t)
        val glyphColor = lerp(startGlyph, scheme.onPrimaryContainer, t)
        val emblemAlpha = (1f - r / 0.6f).coerceIn(0f, 1f)
        val emblemScale = (1f + 0.07f * emblemBeat.value) * (1f + 0.6f * r)

        val unit = SystemSplashIconSize.toPx() / glyph.viewport
        val glyphScale = 1f + (GlyphSettledScale - 1f) * settle.value
        val containerDiameter = glyph.extent * unit * GlyphSettledScale * ContainerToGlyph
        val containerRotation = -70f + 160f * spin.value
        val morphProgress = morph.value

        drawRect(background)

        if (r > 0f) {
            val start = containerDiameter * 0.45f
            val end = hypot(size.width, size.height) * 1.05f
            drawMorph(
                chain = containerMorphs,
                progress = morphProgress,
                scratch = scratch,
                diameter = start + (end - start) * r,
                rotation = containerRotation,
                color = Color.Black,
                blendMode = BlendMode.Clear
            )
        }

        if (emblemAlpha <= 0f) return@Canvas

        drawMorph(
            chain = haloMorphs,
            progress = morphProgress,
            scratch = scratch,
            diameter = containerDiameter * HaloToContainer * enter.value * emblemScale,
            rotation = 40f - 130f * spin.value,
            color = scheme.tertiary.copy(alpha = 0.22f * emblemAlpha * t)
        )
        drawMorph(
            chain = containerMorphs,
            progress = morphProgress,
            scratch = scratch,
            diameter = containerDiameter * enter.value * emblemScale,
            rotation = containerRotation,
            color = scheme.primaryContainer.copy(alpha = emblemAlpha)
        )

        val iconSize = glyph.viewport * unit
        val viewportCenter = Offset(glyph.viewport / 2f, glyph.viewport / 2f)
        val beat = noteBeat.value
        withTransform({
            translate(center.x - iconSize / 2f, center.y - iconSize / 2f)
            scale(unit, unit, pivot = Offset.Zero)
            scale(glyphScale * emblemScale, glyphScale * emblemScale, pivot = viewportCenter)
        }) {
            val color = glyphColor.copy(alpha = glyphColor.alpha * emblemAlpha)
            drawPath(glyph.body, color)
            withTransform({
                translate(0f, -glyph.noteLift * beat)
                rotate(-12f * beat, pivot = glyph.notePivot)
                scale(1f + 0.22f * beat, 1f + 0.22f * beat, pivot = glyph.notePivot)
            }) {
                drawPath(glyph.note, color)
            }
        }
    }
}

/** A sequence of shapes as consecutive morphs; progress `i + f` is `f` of the way from shape i to i+1. */
private fun morphChain(vararg shapes: RoundedPolygon): List<Morph> =
    shapes.toList().zipWithNext { a, b -> Morph(a, b) }

private fun DrawScope.drawMorph(
    chain: List<Morph>,
    progress: Float,
    scratch: android.graphics.Path,
    diameter: Float,
    rotation: Float,
    color: Color,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    if (diameter <= 0f) return
    val index = progress.toInt().coerceIn(0, chain.lastIndex)
    val fraction = (progress - index).coerceIn(0f, 1f)
    scratch.rewind()
    chain[index].toPath(fraction, scratch)
    // Material shapes are normalised to the unit square around (0.5, 0.5).
    withTransform({
        translate(center.x, center.y)
        rotate(rotation, pivot = Offset.Zero)
        scale(diameter, diameter, pivot = Offset.Zero)
        translate(-0.5f, -0.5f)
    }) {
        drawPath(scratch.asComposePath(), color, blendMode = blendMode)
    }
}

/**
 * The app mark split into its two paths so the note can move on its own, in the
 * drawable's viewport units with every group transform already applied.
 */
private class SplashGlyph(
    val body: Path,
    val note: Path,
    val notePivot: Offset,
    /** How far the note lifts on its beat, in viewport units. */
    val noteLift: Float,
    val viewport: Float,
    /** Largest side of the mark's visible bounds, in viewport units. */
    val extent: Float,
)

@Composable
private fun rememberSplashGlyph(): SplashGlyph {
    val vector = ImageVector.vectorResource(R.drawable.splash_icon)
    return remember(vector) {
        val paths = mutableListOf<android.graphics.Path>()
        collectPaths(vector.root, android.graphics.Matrix(), paths)
        // splash_icon draws the body first and the note second.
        val body = paths.first()
        val note = paths.last()

        val bodyBounds = android.graphics.RectF().also { body.computeBounds(it, true) }
        val noteBounds = android.graphics.RectF().also { note.computeBounds(it, true) }
        val all = android.graphics.RectF(bodyBounds).apply { union(noteBounds) }
        SplashGlyph(
            body = body.asComposePath(),
            note = note.asComposePath(),
            notePivot = Offset(noteBounds.centerX(), noteBounds.bottom),
            noteLift = noteBounds.height() * 0.12f,
            viewport = vector.viewportWidth,
            extent = maxOf(all.width(), all.height())
        )
    }
}

/**
 * Flattens a vector's groups into android paths, applying each group's transform the way
 * VectorPainter does: translate(pivot + translation) · rotate · scale · translate(-pivot).
 */
private fun collectPaths(
    group: VectorGroup,
    parent: android.graphics.Matrix,
    out: MutableList<android.graphics.Path>,
) {
    val matrix = android.graphics.Matrix().apply {
        setTranslate(-group.pivotX, -group.pivotY)
        postScale(group.scaleX, group.scaleY)
        postRotate(group.rotation)
        postTranslate(group.translationX + group.pivotX, group.translationY + group.pivotY)
        postConcat(parent)
    }
    for (node in group) {
        when (node) {
            is VectorGroup -> collectPaths(node, matrix, out)
            is VectorPath -> out += node.pathData.toPath().asAndroidPath().apply { transform(matrix) }
        }
    }
}
