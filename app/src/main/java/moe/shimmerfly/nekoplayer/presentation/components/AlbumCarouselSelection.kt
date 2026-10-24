package moe.shimmerfly.nekoplayer.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import coil.size.Size
import moe.shimmerfly.nekoplayer.data.model.Song
import moe.shimmerfly.nekoplayer.data.preferences.CarouselStyle
import moe.shimmerfly.nekoplayer.presentation.components.scoped.PrefetchAlbumNeighbors
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.first

import moe.shimmerfly.nekoplayer.data.preferences.AlbumArtQuality
import kotlin.math.abs

private const val SETTLED_PAGE_OFFSET_EPSILON = 0.001f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberRoundedParallaxCarouselState(
    initialPage: Int,
    pageCount: () -> Int
): CarouselState = rememberCarouselState(initialItem = initialPage, itemCount = pageCount)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumCarouselSection(
    currentSong: Song?,
    queue: ImmutableList<Song>,
    expansionFraction: Float,
    currentMediaItemIndex: Int = -1,
    requestedScrollIndex: Int? = null,
    onSongSelected: (Song, Int) -> Unit,
    onAlbumClick: (Song) -> Unit = {},
    modifier: Modifier = Modifier,
    carouselStyle: String = CarouselStyle.NO_PEEK,
    itemSpacing: Dp = 8.dp,
    albumArtQuality: AlbumArtQuality = AlbumArtQuality.MEDIUM
) {
    if (queue.isEmpty()) return

    val initialIndex = remember(currentSong?.id, currentMediaItemIndex, queue) {
        resolveCurrentQueueIndex(
            currentSong = currentSong,
            currentMediaItemIndex = currentMediaItemIndex,
            queue = queue
        )
    }

    val carouselState = rememberRoundedParallaxCarouselState(
        initialPage = initialIndex,
        pageCount = { queue.size }
    )

    val targetSize = remember(albumArtQuality) {
        if (albumArtQuality.maxSize == 0) SafeOriginalAlbumArtSize
        else Size(albumArtQuality.maxSize, albumArtQuality.maxSize)
    }

    val currentSongIndex = remember(currentSong?.id, currentMediaItemIndex, queue) {
        resolveCurrentQueueIndex(
            currentSong = currentSong,
            currentMediaItemIndex = currentMediaItemIndex,
            queue = queue
        )
    }
    val requestedTargetIndex = remember(requestedScrollIndex, queue) {
        requestedScrollIndex?.takeIf { it in queue.indices }
    }
    val effectiveTargetIndex = requestedTargetIndex ?: currentSongIndex
    val carouselItemKeys = remember(queue) {
        buildQueueOccurrenceKeys(queue)
    }

    PrefetchAlbumNeighbors(
        isActive = expansionFraction > 0.08f,
        pagerState = carouselState.pagerState,
        queue = queue,
        radius = 1,
        targetSize = targetSize,
        anchorIndex = effectiveTargetIndex
    )
    // Scrolls this section runs itself: skips, and the snap when the queue shifts around the
    // playing song. A counter rather than a flag, because a cancelled scroll's cleanup can run
    // after the scroll that replaced it has already started.
    var ownScrollsInFlight by remember { mutableIntStateOf(0) }
    var lastSettledSongId by remember { mutableStateOf(currentSong?.id) }

    // A settle only picks a song after a scroll this section didn't start. User drags come
    // through the pager's interaction source. Accessibility page actions, keyboard and mouse-wheel
    // scrolls emit no DragInteraction, so any scroll that runs while none of ours does counts too.
    // The NO_PEEK settle correction snaps synchronously, so it never shows up as a scroll here.
    var isUserDragging by remember { mutableStateOf(false) }
    var userScrollSettlePending by remember { mutableStateOf(false) }
    LaunchedEffect(carouselState) {
        carouselState.pagerState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    isUserDragging = true
                    userScrollSettlePending = true
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> isUserDragging = false
            }
        }
    }
    LaunchedEffect(carouselState) {
        snapshotFlow { carouselState.pagerState.isScrollInProgress && ownScrollsInFlight == 0 }
            .filter { it }
            .collect { userScrollSettlePending = true }
    }

    // Player -> Carousel. Each new target cancels the in-flight animation (the pager's
    // MutatorMutex) and animates on from the current position, so a burst of skips keeps moving.
    // Only an active user drag is waited for; waiting for our own just-cancelled scroll to go
    // idle stalled the carousel until the taps stopped.
    LaunchedEffect(effectiveTargetIndex, requestedTargetIndex, queue) {
        if (isUserDragging) {
            snapshotFlow { isUserDragging }.first { !it }
        }

        val currentPage = carouselState.pagerState.currentPage
        // A scroll cancelled past the halfway point already reports the target as its current
        // page, so also finish it when it was left between pages.
        val isBetweenPages =
            abs(carouselState.pagerState.currentPageOffsetFraction) > SETTLED_PAGE_OFFSET_EPSILON
        if (currentPage != effectiveTargetIndex || isBetweenPages) {
            val isShiftOnly = currentSong?.id != null &&
                              currentSong.id == lastSettledSongId &&
                              requestedTargetIndex == null

            ownScrollsInFlight++
            try {
                if (isShiftOnly) {
                    carouselState.pagerState.scrollToPage(effectiveTargetIndex)
                } else {
                    carouselState.animateScrollToItem(effectiveTargetIndex)
                }
            } finally {
                ownScrollsInFlight--
            }
        }
        lastSettledSongId = currentSong?.id
    }

    val hapticFeedback = LocalHapticFeedback.current
    LaunchedEffect(carouselState, currentSongIndex, queue) {
        snapshotFlow { carouselState.pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .collect {
                // Only a settle that follows a user scroll picks a song. The player already drove
                // this section's own scrolls, and a skip burst cancels them on intermediate pages.
                if (!userScrollSettlePending) return@collect
                userScrollSettlePending = false
                val settled = carouselState.pagerState.currentPage
                if (settled != currentSongIndex) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    queue.getOrNull(settled)?.let { onSongSelected(it, settled) }
                }
            }
    }

    val corner = 18.dp

    BoxWithConstraints(modifier = modifier) {
        val availableWidth = this.maxWidth

        RoundedHorizontalMultiBrowseCarousel(
            state = carouselState,
            modifier = Modifier.fillMaxSize(),
            itemSpacing = itemSpacing,
            itemCornerRadius = corner,
            suppressNoPeekSettleCorrection = requestedTargetIndex != null || ownScrollsInFlight > 0,
            carouselStyle = if (carouselState.pagerState.pageCount == 1) CarouselStyle.NO_PEEK else carouselStyle,
            carouselWidth = availableWidth,
            itemKey = { index -> carouselItemKeys.getOrNull(index) ?: "queue_item_$index" },
            content = { index ->
                val song = queue[index]
                val isFocusedItem = carouselState.pagerState.currentPage == index
                Box(
                    Modifier
                        .fillMaxSize()
                        .aspectRatio(1f)
                        .clickable(
                            enabled = isFocusedItem && song.albumId != -1L,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onAlbumClick(song) }
                        )
                ) {
                    OptimizedAlbumArt(
                        uri = song.albumArtUriString,
                        title = song.title,
                        modifier = Modifier.fillMaxSize(),
                        targetSize = targetSize
                    )
                }
            }
        )
    }
}

private fun resolveCurrentQueueIndex(
    currentSong: Song?,
    currentMediaItemIndex: Int,
    queue: ImmutableList<Song>
): Int {
    val songId = currentSong?.id ?: return 0
    if (currentMediaItemIndex in queue.indices && queue[currentMediaItemIndex].id == songId) {
        return currentMediaItemIndex
    }
    return queue.indexOfFirst { it.id == songId }
        .takeIf { it >= 0 }
        ?: queue.indexOf(currentSong)
            .takeIf { it >= 0 }
        ?: 0
}

private fun buildQueueOccurrenceKeys(queue: ImmutableList<Song>): List<String> {
    val occurrencesBySongId = HashMap<String, Int>()
    return queue.mapIndexed { index, song ->
        val occurrence = occurrencesBySongId.getOrDefault(song.id, 0)
        occurrencesBySongId[song.id] = occurrence + 1
        "queue_carousel_${song.id}_${occurrence}_${song.albumArtUriString.orEmpty().hashCode()}_$index"
    }
}
