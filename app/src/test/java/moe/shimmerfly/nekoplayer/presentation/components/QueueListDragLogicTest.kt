package moe.shimmerfly.nekoplayer.presentation.components

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueueListDragLogicTest {

    @Test
    fun upwardDragWhileSheetIsPartlyPulledDown_keepsDraggingTheSheet() {
        assertFalse(shouldReturnListDragToList(availableY = -12f, queueSheetOffsetPx = 240f))
    }

    @Test
    fun upwardDragJustShortOfFullyOpen_keepsDraggingTheSheet() {
        assertFalse(shouldReturnListDragToList(availableY = -12f, queueSheetOffsetPx = 0.6f))
    }

    @Test
    fun upwardDragOnceSheetIsFullyOpen_returnsTheGestureToTheList() {
        assertTrue(shouldReturnListDragToList(availableY = -12f, queueSheetOffsetPx = 0f))
        assertTrue(shouldReturnListDragToList(availableY = -12f, queueSheetOffsetPx = 0.5f))
    }

    @Test
    fun downwardDrag_neverReturnsTheGestureToTheList() {
        assertFalse(shouldReturnListDragToList(availableY = 12f, queueSheetOffsetPx = 0f))
        assertFalse(shouldReturnListDragToList(availableY = 12f, queueSheetOffsetPx = 240f))
    }

    @Test
    fun stationaryDelta_neverReturnsTheGestureToTheList() {
        assertFalse(shouldReturnListDragToList(availableY = 0f, queueSheetOffsetPx = 0f))
    }
}
