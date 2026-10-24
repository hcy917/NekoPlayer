package moe.shimmerfly.nekoplayer.presentation.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val SORT_REFRESH_START_TIMEOUT_MS = 1_000L
private const val SORT_REFRESH_FINISH_TIMEOUT_MS = 10_000L

/**
 * Keeps a paged library list at the top whenever its sort option changes.
 *
 * Lazy lists anchor their scroll position to the key of the first visible item, so when the order
 * flips (e.g. ascending -> descending) that item moves to the far end of the new list and the
 * viewport follows it to the bottom. Jumping to the top once right away is not enough: the new
 * paging data lands later and re-anchors the list, so the reset has to be repeated after the
 * refresh completes.
 *
 * The effect is keyed on [sortKey], so toggling rapidly cancels the previous wait and starts a
 * fresh one instead of leaving a stale "pending" flag behind.
 */
@Composable
internal fun ResetScrollOnSortChange(
    sortKey: String,
    items: LazyPagingItems<*>,
    scrollToTop: suspend () -> Unit
) {
    val currentScrollToTop by rememberUpdatedState(scrollToTop)
    var handledSortKey by remember { mutableStateOf(sortKey) }

    LaunchedEffect(sortKey) {
        if (sortKey == handledSortKey) return@LaunchedEffect
        handledSortKey = sortKey

        currentScrollToTop()

        val isRefreshing = snapshotFlow { items.loadState.refresh is LoadState.Loading }
        withTimeoutOrNull(SORT_REFRESH_START_TIMEOUT_MS) { isRefreshing.first { it } }
        withTimeoutOrNull(SORT_REFRESH_FINISH_TIMEOUT_MS) { isRefreshing.first { !it } }

        currentScrollToTop()
    }
}
