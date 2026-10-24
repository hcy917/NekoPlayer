package moe.shimmerfly.nekoplayer.presentation.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Staggered pill rows share one horizontal scroll, keeping their rhythm together. */
@Composable
internal fun <Item> StaggeredPillRows(
    rows: List<List<Item>>,
    /** Fixed pill widths; null lets every pill wrap its own content instead. */
    itemWidth: ((Item) -> Dp)?,
    itemKey: (Item) -> Any,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 58.dp,
    spacing: Dp = 8.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp),
    rowOffsets: List<Dp> = emptyList(),
    content: @Composable (Item, Modifier) -> Unit,
) {
    val direction = LocalLayoutDirection.current
    val start = contentPadding.calculateLeftPadding(direction)
    val end = contentPadding.calculateRightPadding(direction)
    val height = rowHeight * rows.size + spacing * (rows.size - 1).coerceAtLeast(0)
    if (itemWidth == null) {
        // Content-sized pills: every row starts on the same line; the scroll spans the widest row.
        Column(
            modifier.fillMaxWidth().height(height).horizontalScroll(rememberScrollState())
                .padding(start = start, end = end),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            rows.forEachIndexed { index, items ->
                Row(Modifier.height(rowHeight), horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalAlignment = Alignment.CenterVertically) {
                    rowOffsets.getOrNull(index)?.takeIf { it > 0.dp }?.let { Spacer(Modifier.width((it - spacing).coerceAtLeast(0.dp))) }
                    items.forEach { item -> key(itemKey(item)) { content(item, Modifier.height(rowHeight)) } }
                }
            }
        }
        return
    }
    val widths = rows.mapIndexed { index, items ->
        items.fold(start + end + rowOffsets.getOrElse(index) { 0.dp }) { width, item -> width + itemWidth(item) } +
            spacing * (items.size - 1).coerceAtLeast(0)
    }
    val contentWidth = widths.maxOrNull() ?: 0.dp
    Column(
        modifier.fillMaxWidth().height(height)
            .horizontalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        rows.forEachIndexed { index, items ->
            Row(Modifier.width(contentWidth).height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(start + rowOffsets.getOrElse(index) { 0.dp }))
                    items.forEachIndexed { itemIndex, item ->
                        if (itemIndex > 0) Spacer(Modifier.width(spacing))
                        key(itemKey(item)) { content(item, Modifier.width(itemWidth(item)).height(rowHeight)) }
                    }
                    Spacer(Modifier.width(end + (contentWidth - widths[index]).coerceAtLeast(0.dp)))
            }
        }
    }
}
