@file:android.annotation.SuppressLint("RestrictedApi")

package moe.shimmerfly.nekoplayer.presentation.components

import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import moe.shimmerfly.nekoplayer.R
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.google.android.material.color.utilities.Hct
import moe.shimmerfly.nekoplayer.ui.theme.RoundedSans
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Artwork uses the same three palette hues as the rest of the app, at luminous tonal values. */
@Immutable
internal data class NeuralMixPalette(
    val surface: Color,
    val panel: Color,
    val lights: List<Color>,
    val onAction: Color,
    val dark: Boolean,
)

@Composable
internal fun neuralMixPalette(): NeuralMixPalette {
    val c = MaterialTheme.colorScheme
    return remember(c.primary, c.secondary, c.tertiary, c.surface, c.surfaceContainerHigh, c.onPrimary, c.onSurface) {
        val dark = c.surface.luminance() < 0.2f
        fun light(color: Color, tone: Double): Color {
            val hct = Hct.fromInt(color.toArgb())
            // Preserve neutral themes too: don't invent a new accent hue or saturate greys.
            return Color(Hct.from(hct.hue, (hct.chroma * 1.8).coerceAtMost(70.0), tone).toInt())
        }
        val lights = listOf(light(c.primary, 65.0), light(c.tertiary, 60.0), light(c.secondary, 70.0), light(c.primary, 78.0))
        val foreground = listOf(c.onPrimary, c.onSurface, c.surface).maxBy { candidate ->
            lights.minOf { background ->
                (maxOf(candidate.luminance(), background.luminance()) + .05f) /
                    (minOf(candidate.luminance(), background.luminance()) + .05f)
            }
        }
        NeuralMixPalette(c.surface, c.surfaceContainerHigh, lights, foreground, dark)
    }
}

/** Library polygons, used by controls as well as the morphing artwork. */
internal class MixPolygonShape(private val polygon: RoundedPolygon) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = polygon.toPath()
        val bounds = RectF()
        path.computeBounds(bounds, true)
        val matrix = Matrix()
        matrix.setRectToRect(bounds, RectF(0f, 0f, size.width, size.height), Matrix.ScaleToFit.FILL)
        path.transform(matrix)
        return Outline.Generic(path.asComposePath())
    }
}

@Composable
private fun mixPhase(animated: Boolean = true, working: Boolean = false, periodMillis: Int? = null): State<Float>? {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Working and explicit-period clocks are linear loops; the ambient clock eases back and forth.
    val loop = working || periodMillis != null
    return if (animated && lifecycle.isAtLeast(Lifecycle.State.STARTED)) {
        rememberInfiniteTransition(label = "mix_motion").animateFloat(
            0f, (2 * PI).toFloat(),
            infiniteRepeatable(tween(periodMillis ?: if (working) 2600 else 16000,
                easing = if (loop) LinearEasing else FastOutSlowInEasing),
                if (loop) RepeatMode.Restart else RepeatMode.Reverse),
            label = "mix_phase",
        )
    } else null
}

/**
 * A soft three-row colour field: each row is a horizontal gradient through the palette's lights,
 * and the rows blend vertically, like a bicubic mesh of 3 rows by 4 columns. [sway] scales how far
 * the interior points travel. [colorFlow] (0..2π, one lap = the whole palette) slides the colours
 * from point to point, so the swirl visibly moves; 0 is the static palette. Both are read on every
 * draw, and the field is periodic in [phase], so loops never jump.
 */
internal fun mixMesh(
    palette: NeuralMixPalette, phase: () -> Float, dim: Boolean = false, shaded: Boolean = true,
    sway: Float = 1f, colorFlow: () -> Float = { 0f },
): Painter = NeuralMixMeshPainter(palette, phase, dim, shaded, sway, colorFlow)

private class NeuralMixMeshPainter(
    private val palette: NeuralMixPalette,
    private val phase: () -> Float,
    private val dim: Boolean,
    private val shaded: Boolean,
    private val sway: Float,
    private val colorFlow: () -> Float,
) : Painter() {
    private val layerPaint = Paint()
    private val addPaint = Paint().apply { blendMode = BlendMode.Plus }

    override val intrinsicSize: Size get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        val t = phase()
        val flow = colorFlow() / (2 * PI.toFloat()) * palette.lights.size
        fun lightAt(index: Int): Color {
            val position = index + flow
            val base = floor(position).toInt()
            return lerp(palette.lights[base % palette.lights.size], palette.lights[(base + 1) % palette.lights.size], position - base)
        }
        fun colorAt(r: Int, c: Int): Color {
            val light = lightAt((c + r) % 4)
            return when {
                dim && r == 0 -> light.copy(alpha = 0f)
                dim && r == 1 -> light.copy(alpha = if (palette.dark) .20f else .12f)
                dim -> light.copy(alpha = if (palette.dark) .28f else .16f)
                r == 0 && shaded -> lerp(light, palette.surface, .66f)
                r == 1 -> light
                else -> lightAt((c + 3) % 4)
            }
        }
        // The middle row sways vertically; its mean height splits the upper and lower blends.
        var rise = 0f
        for (c in 0..3) rise += cos(t + c * 1.7f) * .14f * sway
        val middle = (.5f + rise / 4f).coerceIn(.2f, .8f)
        val bounds = Rect(Offset.Zero, size)
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, layerPaint)
            for (r in 0..2) {
                val stops = Array(4) { c ->
                    val x = c / 3f + if (r == 1 && c in 1..2) sin(t + c) * .09f * sway else 0f
                    x.coerceIn(0f, 1f) to colorAt(r, c)
                }
                // Row weights sum to one at every height, so the layers add up to a linear blend.
                val weight = when (r) {
                    0 -> Brush.verticalGradient(0f to Color.Black, middle to Color.Transparent, endY = size.height)
                    1 -> Brush.verticalGradient(0f to Color.Transparent, middle to Color.Black, 1f to Color.Transparent, endY = size.height)
                    else -> Brush.verticalGradient(middle to Color.Transparent, 1f to Color.Black, endY = size.height)
                }
                canvas.saveLayer(bounds, addPaint)
                drawRect(Brush.horizontalGradient(*stops, endX = size.width))
                drawRect(weight, blendMode = BlendMode.DstIn)
                canvas.restore()
            }
            canvas.restore()
        }
    }
}

/** Mesh at the lower edge; text and controls have quiet opaque surfaces above it. */
@Composable
internal fun NeuralMixAtmosphere(modifier: Modifier = Modifier, animated: Boolean = true, strength: Float = 1f) {
    val palette = neuralMixPalette()
    val phase = mixPhase(animated)
    val mesh = remember(palette, phase) { mixMesh(palette, { phase?.value ?: 0f }, dim = true) }
    Canvas(modifier) {
        val height = size.height * .32f
        translate(top = size.height - height) {
            with(mesh) { draw(Size(size.width, height), alpha = strength.coerceIn(0f, 1f)) }
        }
    }
}

/**
 * Where the thinking lights sit. The layout reserves only the anchored stage; the lights paint on
 * a larger canvas (the whole card), so their glow spreads freely instead of ending at a rectangle.
 */
@Stable
internal class NeuralMixStage {
    internal var anchor: LayoutCoordinates? = null
    // Bumped on every placement, so a scroll or page move redraws the lights in their new place.
    internal val moves = mutableIntStateOf(0)
}

internal fun Modifier.neuralMixStage(stage: NeuralMixStage) = onGloballyPositioned {
    stage.anchor = it
    stage.moves.intValue++
}

/** One volume's shape: it springs from [from] to [to] (MaterialShapes indices) with a turn. */
@Stable
private class BubbleShape {
    var from by mutableIntStateOf(0)
    var to by mutableIntStateOf(0)
    var morph by mutableStateOf<Morph?>(null)
    val progress = Animatable(1f)
    val rotation = Animatable(0f)
}

/**
 * Symmetric, rounded Expressive shapes only: pills, cookies, clovers, soft bursts. Index 0 is the
 * resting circle. No pointed, pixel or asymmetric shapes, so every morph stays a soft volume.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal val ThinkingShapes: List<RoundedPolygon> by lazy {
    with(MaterialShapes) {
        listOf(Circle, Pill, Cookie4Sided, Clover4Leaf, Cookie6Sided, Sunny, Clover8Leaf,
            SoftBurst, Cookie9Sided, Puffy, PuffyDiamond, Flower, Oval).map { it.normalized() }
    }
}

/** Four resting lights become a breathing cluster; wave dots follow the same continuous clock. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NeuralMixThinking(
    stage: NeuralMixStage,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    visibility: () -> Float = { 1f },
) {
    val palette = neuralMixPalette()
    val phase = mixPhase()
    val wave = mixPhase(working = true)
    // A slow linear clock: resting volumes drift and breathe, so the idle stage feels ready.
    val drift = mixPhase(periodMillis = 9000)
    val workOrigin = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) { if (active) workOrigin.floatValue = wave?.value ?: 0f }
    val activation = animateFloatAsState(if (active) 1f else 0f, spring(.85f, 90f), label = "mix_thinking")
    val bubbles = remember { List(4) { BubbleShape() } }
    val morphs = remember { HashMap<Long, Morph>() }
    fun morphOf(from: Int, to: Int) = morphs.getOrPut(from.toLong() shl 32 or to.toLong()) {
        Morph(ThinkingShapes[from], ThinkingShapes[to])
    }
    LaunchedEffect(active) {
        // The same spring pair as the Expressive loading indicator: a lively morph, a settled turn.
        val morphSpec = spring(dampingRatio = .6f, stiffness = 200f, visibilityThreshold = .01f)
        val turnSpec = spring(dampingRatio = .9f, stiffness = 120f, visibilityThreshold = .5f)
        bubbles.forEachIndexed { index, bubble ->
            launch {
                suspend fun morphTo(next: Int, turn: Float) {
                    bubble.morph = morphOf(bubble.to, next)
                    bubble.from = bubble.to
                    bubble.to = next
                    bubble.progress.snapTo(0f)
                    launch { bubble.rotation.animateTo(bubble.rotation.value + turn, turnSpec) }
                    bubble.progress.animateTo(1f, morphSpec)
                }
                if (active) {
                    val random = Random(System.nanoTime() + index)
                    // Staggered, so the four volumes never change in unison.
                    delay(220L * index + random.nextLong(0, 160))
                    while (true) {
                        var next = random.nextInt(1, ThinkingShapes.size)
                        if (next == bubble.to) next = next % (ThinkingShapes.size - 1) + 1
                        val turn = (if (random.nextBoolean()) 1f else -1f) * random.nextInt(35, 90)
                        morphTo(next, turn)
                        delay(random.nextLong(260, 720))
                    }
                } else if (bubble.to != 0) {
                    morphTo(0, if (bubble.rotation.value % 90f > 0f) 30f else -30f)
                }
            }
        }
    }
    val meshes = remember(palette, phase, wave, activation) {
        List(4) { index ->
            val shiftedPalette = palette.copy(lights = List(4) { palette.lights[(it + index) % 4] })
            mixMesh(shiftedPalette, { (phase?.value ?: 0f) + ((wave?.value ?: 0f) - workOrigin.floatValue) * activation.value })
        }
    }
    var canvas by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Spacer(modifier.onGloballyPositioned { canvas = it }.drawWithCache {
        val dotSpacing = 10.dp.toPx()
        val dotRadius = .65.dp.toPx()
        val path = android.graphics.Path()
        val transform = Matrix()
        val bounds = RectF()
        val layerPaint = Paint()
        onDrawBehind {
            stage.moves.intValue
            val anchor = stage.anchor?.takeIf { it.isAttached } ?: return@onDrawBehind
            val host = canvas?.takeIf { it.isAttached } ?: return@onDrawBehind
            val visible = visibility().coerceIn(0f, 1f)
            if (visible <= 0f) return@onDrawBehind
            val rect = host.localBoundingBoxOf(anchor, clipBounds = false)
            val t = phase?.value ?: 0f
            val d = drift?.value ?: 0f
            val a = activation.value
            val pulse = (wave?.value ?: 0f) - workOrigin.floatValue
            val w = rect.width
            val h = rect.height
            if (w <= 0f || h <= 0f) return@onDrawBehind
            translate(rect.left, rect.top) {
                // A staggered lattice gently folds into a wave, fading towards its edges.
                for (row in 0..(h / dotSpacing).toInt()) for (col in 0..(w / dotSpacing).toInt()) {
                    val x = col * dotSpacing + if (row % 2 == 0) 0f else dotSpacing / 2
                    val envelope = sin(PI * (x / w).coerceIn(0f, 1f)).toFloat()
                    val y = row * dotSpacing + sin(x / w * 7f + t + row * .13f) * (5f + a * 6f) * envelope
                    val fade = (1f - kotlin.math.abs(y / h - .55f) * 1.7f).coerceIn(0f, 1f)
                    drawCircle(palette.lights[2].copy(alpha = .15f * fade * envelope * visible), dotRadius, Offset(x, y))
                }
                for (i in 0..3) {
                    val offset = i * 1.65f
                    val bubble = bubbles[i]
                    // Idle drift: a slow Lissajous loop and a gentle breath, eased out while thinking.
                    val rest = 1f - a
                    val driftX = sin(d + offset) * w * .014f * rest
                    val driftY = sin(2 * d + offset * 1.3f) * h * .06f * rest
                    val breath = 1f + .06f * sin(d + offset * 2.1f) * rest
                    // The crest travels 1 -> 2 -> 3 -> 4. Thinking volumes spread slightly apart
                    // and soften at their rims, so neighbours blend through their light.
                    val crest = .5f + .5f * cos(pulse - i * (PI / 2).toFloat())
                    val radius = (w * (.047f + a * (.023f + .072f * crest * crest)) * breath).coerceAtMost(h * .44f)
                    val center = Offset(w * (.5f + (i - 1.5f) * (.18f + a * .03f)) + driftX,
                        h * .5f + driftY + a * sin(pulse - i * (PI / 2).toFloat()) * h * .08f)
                    // The canvas spans the card, so the halo has room to fall off on its own.
                    val glow = radius * (2.9f + a * .5f)
                    val light = palette.lights[i]
                    drawCircle(Brush.radialGradient(0f to light.copy(alpha = (.40f + a * .12f) * visible),
                        .45f to light.copy(alpha = (.14f + a * .06f) * visible), 1f to Color.Transparent,
                        center = center, radius = glow), glow, center)
                    // Expressive library shapes morph into one another, turning as they change.
                    val morph = bubble.morph
                    if (morph != null) morph.toPath(bubble.progress.value, path)
                    else ThinkingShapes[bubble.to].toPath(path)
                    transform.reset()
                    transform.setScale(radius * 2, radius * 2)
                    transform.postRotate(bubble.rotation.value + a * sin(pulse - offset) * 12f, radius, radius)
                    transform.postTranslate(center.x - radius, center.y - radius)
                    path.transform(transform)
                    // Rotation can extend the morph beyond its original square. Paint its
                    // actual bounds so the mesh never cuts a flat edge into the volume.
                    path.computeBounds(bounds, true)
                    val area = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    // A lighter feather than before, so each new silhouette still reads.
                    val feather = a * .38f
                    layerPaint.alpha = visible
                    drawContext.canvas.saveLayer(area, layerPaint)
                    clipPath(path.asComposePath()) {
                        translate(bounds.left, bounds.top) {
                            with(meshes[i]) { draw(Size(bounds.width(), bounds.height())) }
                        }
                        drawCircle(Brush.radialGradient(listOf(light.copy(alpha = .20f), Color.Transparent),
                            center - Offset(radius * .2f, radius * .35f), radius * 1.4f), radius * 1.5f, center)
                    }
                    if (feather > .01f) {
                        // Fade each rim to transparent while thinking: the volumes read as one field.
                        val rim = maxOf(area.width, area.height) / 2f
                        drawRect(Brush.radialGradient(0f to Color.Black, (1f - feather) to Color.Black,
                            1f to Color.Transparent, center = center, radius = rim),
                            area.topLeft, area.size, blendMode = BlendMode.DstIn)
                    }
                    drawContext.canvas.restore()
                }
            }
        }
    })
}

/** Small, recognizable music mark in a Material shape, replacing the generic AI sparkle. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NeuralMixEmblem(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = remember { MixPolygonShape(MaterialShapes.PuffyDiamond) }
    Box(modifier.size(40.dp).clip(shape).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_smart_mix), null, Modifier.size(22.dp), tint = colors.onPrimaryContainer)
    }
}

/** Borderless mesh capsule, with M3 interaction semantics and spring compression on press. */
@Composable
internal fun NeuralMixButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
    busy: Boolean = false,
    shape: Shape = RoundedCornerShape(50),
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = neuralMixPalette()
    val colors = MaterialTheme.colorScheme
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = remember { MotionScheme.expressive() }
    val compression = animateFloatAsState(if (pressed) .97f else 1f, motion.fastSpatialSpec(), label = "mix_press")
    val illuminated = !secondary && (enabled || busy)
    // Two slow linear loops: the points sway, and the colours drift around the palette. Both are
    // seamless at their wrap, and stop off-screen (not STARTED) or when the button is unlit.
    val phase = mixPhase(illuminated, periodMillis = 11000)
    val flow = mixPhase(illuminated, periodMillis = 32000)
    val mesh = remember(palette, phase, flow) {
        mixMesh(palette, { phase?.value ?: 0f }, shaded = false, sway = 1.3f, colorFlow = { flow?.value ?: 0f })
    }
    val illumination = animateFloatAsState(if (illuminated) 1f else 0f,
        motion.defaultEffectsSpec(), label = "mix_action_light")
    Button(
        onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 60.dp).graphicsLayer {
            scaleX = compression.value; scaleY = compression.value
        }.clip(shape).drawWithCache {
            onDrawBehind {
                drawRect(if (secondary) colors.secondaryContainer else palette.panel)
                if (illumination.value > 0f) {
                    with(mesh) { draw(size, alpha = illumination.value) }
                    // Soft glass depth, not a stroked perimeter.
                    drawRect(Brush.verticalGradient(listOf(palette.surface.copy(alpha = .20f), Color.Transparent), endY = size.height * .72f))
                }
            }
        },
        interactionSource = interaction,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = if (secondary) colors.onSecondaryContainer else palette.onAction,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = if (busy) {
                if (secondary) colors.onSecondaryContainer else palette.onAction
            } else colors.onSurface.copy(alpha = .38f),
        ),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
internal fun NeuralMixChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val motion = remember { MotionScheme.expressive() }
    val corner by animateDpAsState(if (selected) 16.dp else 28.dp,
        motion.fastSpatialSpec(), label = "mix_choice_shape")
    FilterChip(
        selected, onClick, enabled = enabled, shape = RoundedCornerShape(corner),
        label = { Text(label, fontFamily = RoundedSans) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surfaceContainerHigh,
            selectedContainerColor = colors.secondaryContainer,
            selectedLabelColor = colors.onSecondaryContainer,
        ),
        border = null,
    )
}
