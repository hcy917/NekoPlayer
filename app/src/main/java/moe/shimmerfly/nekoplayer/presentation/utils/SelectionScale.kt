package moe.shimmerfly.nekoplayer.presentation.utils

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Scales a selectable card as a single flattened layer.
 *
 * A plain `Modifier.scale` re-rasterizes every child at fractional pixel edges, so cards
 * built from stacked pieces (artwork + gradient overlay + solid text panel) show a thin
 * anti-aliasing seam where the pieces meet while the card is shrunk for selection. Rendering
 * the card offscreen first and scaling the result keeps those edges pixel-aligned. The
 * offscreen layer is only requested while the card is actually scaled.
 */
fun Modifier.seamlessSelectionScale(scale: Float): Modifier = graphicsLayer {
    scaleX = scale
    scaleY = scale
    compositingStrategy = if (scale != 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
}
