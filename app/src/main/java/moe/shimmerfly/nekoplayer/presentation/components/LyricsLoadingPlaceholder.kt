package moe.shimmerfly.nekoplayer.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import moe.shimmerfly.nekoplayer.R

/**
 * Shimmering skeleton of lyric lines shown while lyrics load. The shimmer offset is only
 * read inside the draw lambda, so the animation redraws the bars without recomposing.
 */
@Composable
internal fun LyricsLoadingPlaceholder(
    contentColor: Color,
    accentColor: Color,
    horizontalAlignment: Alignment.Horizontal,
    modifier: Modifier = Modifier,
) {
    val loadingDescription = stringResource(R.string.loading_lyrics)
    val transition = rememberInfiniteTransition(label = "lyricsLoadingShimmer")
    val shimmerOffset = transition.animateFloat(
        initialValue = -700f,
        targetValue = 1_500f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_250, easing = LinearEasing),
        ),
        label = "lyricsLoadingOffset",
    )
    val shimmerColors = listOf(
        contentColor.copy(alpha = 0.10f),
        contentColor.copy(alpha = 0.28f),
        accentColor.copy(alpha = 0.34f),
        contentColor.copy(alpha = 0.10f),
    )
    val widths = listOf(0.72f, 0.91f, 0.64f, 0.83f, 0.56f, 0.76f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = loadingDescription },
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        widths.forEachIndexed { index, widthFraction ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(widthFraction)
                    .height(if (index == 2) 32.dp else 28.dp)
                    .drawWithCache {
                        val cornerRadius = CornerRadius(10.dp.toPx())
                        onDrawBehind {
                            val offset = shimmerOffset.value
                            drawRoundRect(
                                brush = Brush.horizontalGradient(
                                    colors = shimmerColors,
                                    startX = offset,
                                    endX = offset + 520f,
                                ),
                                cornerRadius = cornerRadius,
                            )
                        }
                    },
            )
        }
    }
}
