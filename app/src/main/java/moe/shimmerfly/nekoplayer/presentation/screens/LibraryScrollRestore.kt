package moe.shimmerfly.nekoplayer.presentation.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val RESTORE_TIMEOUT_MS = 5_000L

/**
 * A list state for a paged library tab that keeps its scroll position when the screen is left
 * (an album opened, then back) and comes back.
 *
 * `rememberLazyListState` already saves the position, but on return the paged items are still
 * loading, so the list is empty when it tries to restore and clamps the position to the top. This
 * keeps the saved position itself and applies it once the items are there. Until then nothing is
 * saved, so the top of the still-empty list can't overwrite it; after that every scroll is saved,
 * including the reset to the top when the sort changes ([ResetScrollOnSortChange]).
 */
@Composable
internal fun rememberRestoredLazyListState(items: LazyPagingItems<*>): LazyListState {
    val state = remember { LazyListState() }
    RestoreScroll(items, { state.firstVisibleItemIndex }, { state.firstVisibleItemScrollOffset },
        { index, offset -> state.scrollToItem(index, offset) })
    return state
}

@Composable
internal fun rememberRestoredLazyGridState(items: LazyPagingItems<*>): LazyGridState {
    val state = remember { LazyGridState() }
    RestoreScroll(items, { state.firstVisibleItemIndex }, { state.firstVisibleItemScrollOffset },
        { index, offset -> state.scrollToItem(index, offset) })
    return state
}

@Composable
private fun RestoreScroll(
    items: LazyPagingItems<*>,
    index: () -> Int,
    offset: () -> Int,
    scrollTo: suspend (Int, Int) -> Unit,
) {
    var savedIndex by rememberSaveable { mutableIntStateOf(0) }
    var savedOffset by rememberSaveable { mutableIntStateOf(0) }
    var restored by remember { mutableStateOf(savedIndex == 0 && savedOffset == 0) }

    LaunchedEffect(Unit) {
        if (restored) return@LaunchedEffect
        // Give up (and start saving again) if the items never reach the saved position, e.g. the
        // library shrank while away.
        val ready = withTimeoutOrNull(RESTORE_TIMEOUT_MS) { snapshotFlow { items.itemCount }.first { it > savedIndex } }
        // A list the user already scrolled keeps its position.
        if (ready != null && index() == 0 && offset() == 0) scrollTo(savedIndex, savedOffset)
        restored = true
    }

    LaunchedEffect(restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { index() to offset() }.collect { (i, o) ->
            savedIndex = i
            savedOffset = o
        }
    }
}
