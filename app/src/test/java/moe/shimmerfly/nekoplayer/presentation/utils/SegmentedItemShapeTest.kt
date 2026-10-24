package moe.shimmerfly.nekoplayer.presentation.utils

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SegmentedItemShapeTest {

    private val density = Density(1f)
    private val dummySize = Size(100f, 100f)

    @Test
    fun singleItem_returnsSingleShapeWithAllOuterCorners() {
        val shape = getSegmentedItemShape(
            index = 0,
            count = 1,
            outerRadius = SegmentedItemShapeDefaults.SongOuterCornerRadius
        )
        assertSame(SegmentedItemShapeDefaults.SongSingleShape, shape)
        assertEquals(24f, shape.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(24f, shape.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(24f, shape.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(24f, shape.bottomEnd.toPx(dummySize, density), 0.01f)
    }

    @Test
    fun zeroItems_returnsSingleShapeWithAllOuterCorners() {
        val shape = getSegmentedItemShape(
            index = 0,
            count = 0,
            outerRadius = SegmentedItemShapeDefaults.CardOuterCornerRadius
        )
        assertSame(SegmentedItemShapeDefaults.CardSingleShape, shape)
        assertEquals(16f, shape.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(16f, shape.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(16f, shape.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(16f, shape.bottomEnd.toPx(dummySize, density), 0.01f)
    }

    @Test
    fun twoItems_firstItemHasOuterTopAndInnerBottom() {
        val shape = getSegmentedItemShape(
            index = 0,
            count = 2,
            outerRadius = SegmentedItemShapeDefaults.SongOuterCornerRadius
        )
        assertSame(SegmentedItemShapeDefaults.SongTopShape, shape)
        assertEquals(24f, shape.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(24f, shape.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(8f, shape.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, shape.bottomEnd.toPx(dummySize, density), 0.01f)
    }

    @Test
    fun twoItems_lastItemHasInnerTopAndOuterBottom() {
        val shape = getSegmentedItemShape(
            index = 1,
            count = 2,
            outerRadius = SegmentedItemShapeDefaults.SongOuterCornerRadius
        )
        assertSame(SegmentedItemShapeDefaults.SongBottomShape, shape)
        assertEquals(8f, shape.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, shape.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(24f, shape.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(24f, shape.bottomEnd.toPx(dummySize, density), 0.01f)
    }

    @Test
    fun multipleItems_middleItemsHaveInnerCorners() {
        val count = 5
        val first = getSegmentedItemShape(0, count, SegmentedItemShapeDefaults.CardOuterCornerRadius)
        val middle1 = getSegmentedItemShape(1, count, SegmentedItemShapeDefaults.CardOuterCornerRadius)
        val middle2 = getSegmentedItemShape(2, count, SegmentedItemShapeDefaults.CardOuterCornerRadius)
        val middle3 = getSegmentedItemShape(3, count, SegmentedItemShapeDefaults.CardOuterCornerRadius)
        val last = getSegmentedItemShape(4, count, SegmentedItemShapeDefaults.CardOuterCornerRadius)

        assertSame(SegmentedItemShapeDefaults.CardTopShape, first)
        assertSame(SegmentedItemShapeDefaults.MiddleShape, middle1)
        assertSame(SegmentedItemShapeDefaults.MiddleShape, middle2)
        assertSame(SegmentedItemShapeDefaults.MiddleShape, middle3)
        assertSame(SegmentedItemShapeDefaults.CardBottomShape, last)

        assertEquals(16f, first.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, first.bottomEnd.toPx(dummySize, density), 0.01f)

        assertEquals(8f, middle1.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, middle1.bottomEnd.toPx(dummySize, density), 0.01f)

        assertEquals(8f, last.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(16f, last.bottomEnd.toPx(dummySize, density), 0.01f)
    }

    @Test
    fun customRadii_computeCorrectly() {
        val customOuter = 20.dp
        val customInner = 6.dp
        val first = getSegmentedItemShape(0, 3, outerRadius = customOuter, innerRadius = customInner)
        val middle = getSegmentedItemShape(1, 3, outerRadius = customOuter, innerRadius = customInner)
        val last = getSegmentedItemShape(2, 3, outerRadius = customOuter, innerRadius = customInner)

        assertEquals(20f, first.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(6f, first.bottomEnd.toPx(dummySize, density), 0.01f)

        assertEquals(6f, middle.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(6f, middle.bottomEnd.toPx(dummySize, density), 0.01f)

        assertEquals(6f, last.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(20f, last.bottomEnd.toPx(dummySize, density), 0.01f)
    }

    @Test
    fun defaultOuterWithCustomInner_keepsCustomInnerCorners() {
        val first = getSegmentedItemShape(0, 3, innerRadius = 4.dp)
        val middle = getSegmentedItemShape(1, 3, innerRadius = 4.dp)
        val last = getSegmentedItemShape(2, 3, innerRadius = 4.dp)

        assertEquals(24f, first.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(4f, first.bottomEnd.toPx(dummySize, density), 0.01f)
        assertEquals(4f, middle.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(4f, last.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(24f, last.bottomEnd.toPx(dummySize, density), 0.01f)
    }

    @Test
    fun singleItem_grid_returnsAllOuterCorners() {
        val shape = getSegmentedGridItemShape(
            index = 0,
            count = 1,
            columns = 2,
            outerRadius = 20.dp,
            innerRadius = 8.dp
        )
        assertEquals(20f, shape.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(20f, shape.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(20f, shape.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(20f, shape.bottomEnd.toPx(dummySize, density), 0.01f)
    }

    @Test
    fun zeroItems_grid_returnsAllOuterCorners() {
        val shape = getSegmentedGridItemShape(
            index = 0,
            count = 0,
            columns = 2,
            outerRadius = 20.dp,
            innerRadius = 8.dp
        )
        assertEquals(20f, shape.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(20f, shape.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(20f, shape.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(20f, shape.bottomEnd.toPx(dummySize, density), 0.01f)
    }

    @Test
    fun twoItems_grid_columns2_returnsCorrectOuterCorners() {
        val count = 2
        val item0 = getSegmentedGridItemShape(0, count, columns = 2, outerRadius = 20.dp, innerRadius = 8.dp)
        val item1 = getSegmentedGridItemShape(1, count, columns = 2, outerRadius = 20.dp, innerRadius = 8.dp)

        // item 0: top-left of grid, bottom-left of grid
        assertEquals(20f, item0.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item0.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(20f, item0.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item0.bottomEnd.toPx(dummySize, density), 0.01f)

        // item 1: top-right of grid, bottom-right of grid
        assertEquals(8f, item1.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(20f, item1.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item1.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(20f, item1.bottomEnd.toPx(dummySize, density), 0.01f)
    }

    @Test
    fun threeItems_grid_columns2_oddCount_returnsCorrectCorners() {
        val count = 3
        val item0 = getSegmentedGridItemShape(0, count, columns = 2, outerRadius = 20.dp, innerRadius = 8.dp)
        val item1 = getSegmentedGridItemShape(1, count, columns = 2, outerRadius = 20.dp, innerRadius = 8.dp)
        val item2 = getSegmentedGridItemShape(2, count, columns = 2, outerRadius = 20.dp, innerRadius = 8.dp)

        // item 0: row 0, col 0 (top-left)
        assertEquals(20f, item0.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item0.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item0.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item0.bottomEnd.toPx(dummySize, density), 0.01f)

        // item 1: row 0, col 1 (top-right, and closes col 1 bottom)
        assertEquals(8f, item1.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(20f, item1.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item1.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(20f, item1.bottomEnd.toPx(dummySize, density), 0.01f)

        // item 2: row 1, col 0 (closes col 0 bottom)
        assertEquals(8f, item2.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item2.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(20f, item2.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item2.bottomEnd.toPx(dummySize, density), 0.01f)
    }

    @Test
    fun fourItems_grid_columns2_evenCount_returnsCorrectCorners() {
        val count = 4
        val item0 = getSegmentedGridItemShape(0, count, columns = 2, outerRadius = 20.dp, innerRadius = 8.dp)
        val item1 = getSegmentedGridItemShape(1, count, columns = 2, outerRadius = 20.dp, innerRadius = 8.dp)
        val item2 = getSegmentedGridItemShape(2, count, columns = 2, outerRadius = 20.dp, innerRadius = 8.dp)
        val item3 = getSegmentedGridItemShape(3, count, columns = 2, outerRadius = 20.dp, innerRadius = 8.dp)

        // item 0: topStart = 20
        assertEquals(20f, item0.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item0.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item0.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item0.bottomEnd.toPx(dummySize, density), 0.01f)

        // item 1: topEnd = 20
        assertEquals(8f, item1.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(20f, item1.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item1.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item1.bottomEnd.toPx(dummySize, density), 0.01f)

        // item 2: bottomStart = 20
        assertEquals(8f, item2.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item2.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(20f, item2.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item2.bottomEnd.toPx(dummySize, density), 0.01f)

        // item 3: bottomEnd = 20
        assertEquals(8f, item3.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item3.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item3.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(20f, item3.bottomEnd.toPx(dummySize, density), 0.01f)
    }

    @Test
    fun fiveItems_grid_columns2_oddCount_returnsCorrectCorners() {
        val count = 5
        val item0 = getSegmentedGridItemShape(0, count, columns = 2, outerRadius = 20.dp, innerRadius = 8.dp)
        val item1 = getSegmentedGridItemShape(1, count, columns = 2, outerRadius = 20.dp, innerRadius = 8.dp)
        val item2 = getSegmentedGridItemShape(2, count, columns = 2, outerRadius = 20.dp, innerRadius = 8.dp)
        val item3 = getSegmentedGridItemShape(3, count, columns = 2, outerRadius = 20.dp, innerRadius = 8.dp)
        val item4 = getSegmentedGridItemShape(4, count, columns = 2, outerRadius = 20.dp, innerRadius = 8.dp)

        // item 0: topStart = 20
        assertEquals(20f, item0.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item0.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item0.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item0.bottomEnd.toPx(dummySize, density), 0.01f)

        // item 1: topEnd = 20
        assertEquals(8f, item1.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(20f, item1.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item1.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item1.bottomEnd.toPx(dummySize, density), 0.01f)

        // item 2: all 8dp
        assertEquals(8f, item2.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item2.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item2.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item2.bottomEnd.toPx(dummySize, density), 0.01f)

        // item 3: row 1, col 1 (closes col 1 bottom -> bottomEnd = 20)
        assertEquals(8f, item3.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item3.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item3.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(20f, item3.bottomEnd.toPx(dummySize, density), 0.01f)

        // item 4: row 2, col 0 (closes col 0 bottom -> bottomStart = 20)
        assertEquals(8f, item4.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item4.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(20f, item4.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item4.bottomEnd.toPx(dummySize, density), 0.01f)
    }

    @Test
    fun singleColumn_grid_matchesSegmentedListBehavior() {
        val count = 3
        val item0 = getSegmentedGridItemShape(0, count, columns = 1, outerRadius = 16.dp, innerRadius = 8.dp)
        val item1 = getSegmentedGridItemShape(1, count, columns = 1, outerRadius = 16.dp, innerRadius = 8.dp)
        val item2 = getSegmentedGridItemShape(2, count, columns = 1, outerRadius = 16.dp, innerRadius = 8.dp)

        // item 0: topStart = 16, topEnd = 16, bottomStart = 8, bottomEnd = 8
        assertEquals(16f, item0.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(16f, item0.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item0.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item0.bottomEnd.toPx(dummySize, density), 0.01f)

        // item 1: all 8
        assertEquals(8f, item1.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item1.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item1.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item1.bottomEnd.toPx(dummySize, density), 0.01f)

        // item 2: topStart = 8, topEnd = 8, bottomStart = 16, bottomEnd = 16
        assertEquals(8f, item2.topStart.toPx(dummySize, density), 0.01f)
        assertEquals(8f, item2.topEnd.toPx(dummySize, density), 0.01f)
        assertEquals(16f, item2.bottomStart.toPx(dummySize, density), 0.01f)
        assertEquals(16f, item2.bottomEnd.toPx(dummySize, density), 0.01f)
    }
}
