package moe.shimmerfly.nekoplayer.presentation.components.scoped

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerSheetHorizontalLayoutTest {

    // Sheet width beside a navigation rail, so it is deliberately not a screen width.
    private val sheetWidthPx = 1784

    /** Mirrors the card's outer layout: the card is inset by the start and end padding. */
    private fun cardInnerWidthPx(startPaddingPx: Int, endPaddingPx: Int): Int =
        (sheetWidthPx - startPaddingPx - endPaddingPx).coerceAtLeast(0)

    @Test
    fun expandingPlayerContent_isMeasuredAtTheSheetWidthAtEveryPadding() {
        // Collapsed padding, mid-animation values and the expanded state (no padding).
        listOf(36.75f, 31.2f, 18.9f, 7.4f, 0.6f, 0f).forEach { paddingValue ->
            val startPaddingPx = sheetHorizontalPaddingPx(paddingValue)
            val endPaddingPx = sheetHorizontalPaddingPx(paddingValue)
            val cardWidthPx = cardInnerWidthPx(startPaddingPx, endPaddingPx)

            listOf(0.01f, 0.5f, 1f).forEach { fraction ->
                assertEquals(
                    sheetWidthPx,
                    playerContentWidthPx(cardWidthPx, startPaddingPx, endPaddingPx, fraction),
                    "padding=$paddingValue fraction=$fraction"
                )
            }
        }
    }

    @Test
    fun expandingPlayerContent_isShiftedBackToTheSheetEdge() {
        assertEquals(-36, playerContentOffsetXPx(startPaddingPx = 36, expansionFraction = 0.3f))
    }

    @Test
    fun miniPlayer_staysAlignedWithTheCardWhileExpanding() {
        listOf(36 to 36, 24 to 12, 5 to 0, 0 to 0).forEach { (startPaddingPx, endPaddingPx) ->
            val cardWidthPx = cardInnerWidthPx(startPaddingPx, endPaddingPx)
            val fraction = 0.4f
            val contentWidthPx =
                playerContentWidthPx(cardWidthPx, startPaddingPx, endPaddingPx, fraction)
            val miniWidthPx =
                miniPlayerLayerWidthPx(contentWidthPx, startPaddingPx, endPaddingPx, fraction)

            assertEquals(contentWidthPx, miniWidthPx + startPaddingPx + endPaddingPx)
            assertEquals(cardWidthPx, miniWidthPx)
            // The content sits at -start inside the card and the mini player at +start inside
            // the content, so the mini player starts exactly at the card's edge.
            assertEquals(
                0,
                playerContentOffsetXPx(startPaddingPx, fraction) +
                    miniPlayerLayerOffsetXPx(startPaddingPx, fraction)
            )
        }
    }

    @Test
    fun collapsedPlayer_keepsTheCardWidthWithoutOffsets() {
        val cardWidthPx = cardInnerWidthPx(36, 36)

        assertFalse(measuresPlayerContentAtFullWidth(0f))
        assertEquals(cardWidthPx, playerContentWidthPx(cardWidthPx, 36, 36, expansionFraction = 0f))
        assertEquals(0, playerContentOffsetXPx(36, expansionFraction = 0f))
        assertEquals(cardWidthPx, miniPlayerLayerWidthPx(cardWidthPx, 36, 36, expansionFraction = 0f))
        assertEquals(0, miniPlayerLayerOffsetXPx(36, expansionFraction = 0f))
    }

    @Test
    fun anyExpansion_switchesToTheFullWidthLayout() {
        assertTrue(measuresPlayerContentAtFullWidth(0.001f))
        assertTrue(measuresPlayerContentAtFullWidth(1f))
    }

    @Test
    fun negativePadding_isTreatedAsNoPadding() {
        assertEquals(0, sheetHorizontalPaddingPx(-3.7f))
        assertEquals(12, sheetHorizontalPaddingPx(12.9f))

        assertEquals(sheetWidthPx, playerContentWidthPx(sheetWidthPx, -8, -8, expansionFraction = 1f))
        assertEquals(0, playerContentOffsetXPx(-8, expansionFraction = 1f))
        assertEquals(sheetWidthPx, miniPlayerLayerWidthPx(sheetWidthPx, -8, -8, expansionFraction = 1f))
        assertEquals(0, miniPlayerLayerOffsetXPx(-8, expansionFraction = 1f))
    }

    @Test
    fun paddingWiderThanTheContent_collapsesTheMiniPlayerToZeroWidth() {
        assertEquals(0, miniPlayerLayerWidthPx(100, 80, 80, expansionFraction = 0.5f))
    }
}
