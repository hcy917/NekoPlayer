package moe.shimmerfly.nekoplayer.presentation.components.scoped

// Horizontal layout of the mini and full player layers inside the floating player card.
//
// The card's outer layout insets the card by the animated start/end padding. While the player is
// expanding, expanded or collapsing (expansion fraction > 0), the layers are measured at the
// card's inner width plus that padding instead, which is the sheet's own width in every frame,
// and shifted back by the start padding. The full player then keeps one stable layout through
// the transition and the card only clips it. The width comes from the card constraints rather
// than the screen, so it stays right when the sheet sits beside the navigation rail. The mini
// player is inset again by the same padding, which keeps it aligned with the card.

/** Converts a padding provider value exactly like the card's outer layout does. */
internal fun sheetHorizontalPaddingPx(paddingPx: Float): Int = paddingPx.toInt().coerceAtLeast(0)

internal fun measuresPlayerContentAtFullWidth(expansionFraction: Float): Boolean =
    expansionFraction > 0f

internal fun playerContentWidthPx(
    cardInnerWidthPx: Int,
    startPaddingPx: Int,
    endPaddingPx: Int,
    expansionFraction: Float
): Int = if (measuresPlayerContentAtFullWidth(expansionFraction)) {
    cardInnerWidthPx + startPaddingPx.coerceAtLeast(0) + endPaddingPx.coerceAtLeast(0)
} else {
    cardInnerWidthPx
}

/** X offset of the player content relative to the card; mirrored by placeRelative in RTL. */
internal fun playerContentOffsetXPx(startPaddingPx: Int, expansionFraction: Float): Int =
    if (measuresPlayerContentAtFullWidth(expansionFraction)) -startPaddingPx.coerceAtLeast(0) else 0

internal fun miniPlayerLayerWidthPx(
    playerContentWidthPx: Int,
    startPaddingPx: Int,
    endPaddingPx: Int,
    expansionFraction: Float
): Int = if (measuresPlayerContentAtFullWidth(expansionFraction)) {
    (playerContentWidthPx - startPaddingPx.coerceAtLeast(0) - endPaddingPx.coerceAtLeast(0))
        .coerceAtLeast(0)
} else {
    playerContentWidthPx
}

/** X offset of the mini player relative to the player content; mirrored by placeRelative in RTL. */
internal fun miniPlayerLayerOffsetXPx(startPaddingPx: Int, expansionFraction: Float): Int =
    if (measuresPlayerContentAtFullWidth(expansionFraction)) startPaddingPx.coerceAtLeast(0) else 0
