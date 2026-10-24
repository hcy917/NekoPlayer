package moe.shimmerfly.nekoplayer.data.service

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import moe.shimmerfly.nekoplayer.data.model.QueueItem

internal fun widgetQueuePreviewIndices(
    firstIndex: Int,
    limit: Int,
    nextIndex: (Int) -> Int,
): List<Int> {
    if (firstIndex == C.INDEX_UNSET || limit <= 0) return emptyList()

    val visited = LinkedHashSet<Int>(limit)
    var currentIndex = firstIndex
    while (
        currentIndex != C.INDEX_UNSET &&
        visited.size < limit &&
        visited.add(currentIndex)
    ) {
        currentIndex = nextIndex(currentIndex)
    }
    return visited.toList()
}

internal fun MediaItem.toWidgetQueueItem(
    fallbackTitle: String = "",
    fallbackArtist: String = "",
    fallbackArtworkUri: String? = null,
): QueueItem? {
    val songId = mediaId.toLongOrNull() ?: return null
    val title = sequenceOf(
        mediaMetadata.title,
        mediaMetadata.displayTitle,
    )
        .mapNotNull { value -> value?.toString()?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull()
        .orEmpty()
        .ifBlank { fallbackTitle.trim() }
    val artist = mediaMetadata.artist
        ?.toString()
        ?.trim()
        .orEmpty()
        .ifBlank { fallbackArtist.trim() }
    val artworkUri = fallbackArtworkUri
        ?: mediaMetadata.artworkUri?.toString()

    return QueueItem(
        id = songId,
        albumArtUri = artworkUri,
        title = title,
        artist = artist,
    )
}
