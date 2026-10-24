package moe.shimmerfly.nekoplayer.presentation.viewmodel

import moe.shimmerfly.nekoplayer.data.model.Song
import moe.shimmerfly.nekoplayer.utils.QueueUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages queue shuffle state.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * This class handles the original queue order for shuffle/unshuffle operations.
 */
@Singleton
class QueueStateHolder @Inject constructor() {

    private var _originalQueueOrder: List<Song> = emptyList()
    val originalQueueOrder: List<Song> get() = _originalQueueOrder

    private var _originalQueuePlaylistIds: List<String?>? = null

    /**
     * The playlist each entry of [originalQueueOrder] was queued from, by position, with null for
     * an entry that has none. Null when that is not known. Kept by position because the order can
     * hold a song twice, once from a playlist and once added by hand, and only the place of each
     * copy tells them apart.
     */
    val originalQueuePlaylistIds: List<String?>? get() = _originalQueuePlaylistIds

    /**
     * Set original queue order (for updates during playback), with the playlist of each entry
     * when known. [playlistIds] that do not line up with [queue] are dropped.
     */
    fun setOriginalQueueOrder(queue: List<Song>, playlistIds: List<String?>? = null) {
        _originalQueueOrder = queue.toList()
        _originalQueuePlaylistIds = playlistIds?.takeIf { it.size == queue.size }?.toList()
    }

    /**
     * Check if original queue is empty.
     */
    fun hasOriginalQueue(): Boolean = _originalQueueOrder.isNotEmpty()

    /**
     * Prepares a list for shuffled playback.
     * 1. Saves original queue, with every entry queued from [playlistId].
     * 2. Picks a random start song.
     * 3. Creates a shuffled list starting with that song.
     * Runs the heavy shuffle computation on Default dispatcher to avoid UI stalls.
     */
    suspend fun prepareShuffledQueueSuspending(
        songs: List<Song>,
        startAtZero: Boolean = false,
        playlistId: String? = null
    ): Pair<List<Song>, Song>? {
        if (songs.isEmpty()) return null

        val startSong = songs.random()
        setOriginalQueueOrder(songs, List(songs.size) { playlistId })

        val startIndex = songs.indexOfFirst { it.id == startSong.id }.coerceAtLeast(0)
        val shuffledQueue = withContext(Dispatchers.Default) {
            QueueUtils.buildAnchoredShuffleQueueSuspending(songs, startIndex, startAtZero)
        }
        return Pair(shuffledQueue, startSong)
    }
}
