package moe.shimmerfly.nekoplayer.presentation.viewmodel

/**
 * Hands the playlist tags of the queue entries a rebuild replaces back out, one per entry.
 *
 * A song can sit in the queue more than once with different tags, for example once from the
 * playlist and once added by hand through Play next. Looking tags up by song id alone would give
 * the hand-added copy the playlist's tag. The pool keeps one tag per tagged entry instead, so a
 * song never comes back with more tagged copies than it had, and a song the player did not hold
 * gets no tag.
 *
 * The pool knows how many copies of a song carry each tag, not where each copy belongs. That is
 * enough for a shuffle, which places the copies at random anyway. A rebuild into an order that
 * knows its entries' own tags, such as the original order turning shuffle off goes back to,
 * uses those by position instead. Tags are handed out before untagged copies, so when a rebuild
 * keeps fewer copies of a song than the player held, the copy that stays keeps the tag.
 *
 * @param entries each queue entry's media id and playlist tag in player order, null for an
 * entry with no tag.
 */
internal class PlaylistTagPool(entries: List<Pair<String, String?>>) {

    private val tagsByMediaId = HashMap<String, ArrayDeque<String>>()

    init {
        for ((mediaId, playlistId) in entries) {
            if (playlistId != null) {
                tagsByMediaId.getOrPut(mediaId) { ArrayDeque() }.addLast(playlistId)
            }
        }
    }

    /** The tag for the next rebuilt entry of [mediaId], or null when none is left for it. */
    fun take(mediaId: String): String? = tagsByMediaId[mediaId]?.removeFirstOrNull()

    /** Sets aside [playlistId] for an entry of [mediaId] the rebuild keeps as it is. */
    fun consume(mediaId: String, playlistId: String?) {
        if (playlistId == null) return
        tagsByMediaId[mediaId]?.remove(playlistId)
    }
}
