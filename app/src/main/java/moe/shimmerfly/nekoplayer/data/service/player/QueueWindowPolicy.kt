package moe.shimmerfly.nekoplayer.data.service.player

/**
 * Arithmetic behind the crossfade queue window.
 *
 * [DualPlayerEngine] prepares the incoming crossfade player with only a slice of the queue
 * so preparing a transition stays cheap on very large queues. That slice becomes the master
 * timeline once the players swap, and the master timeline *is* the app's queue — the
 * MediaSession, the queue sheet, absolute-index queue edits and the snapshot persisted for
 * process death all read it. It therefore has to be spliced back to the full queue right
 * after the swap, or the queue silently shrinks to the window and the truncation is saved.
 *
 * Kept separate from the engine (like [AudioDecoderPolicy]) so the index maths is unit
 * testable without an ExoPlayer.
 */
internal object QueueWindowPolicy {

    /** Largest timeline handed to the auxiliary player while preparing a crossfade. */
    const val MAX_AUXILIARY_TIMELINE_ITEMS = 200

    /**
     * Half-open `[start, end)` slice of a [count]-item queue to prepare around
     * [targetIndex]. Returns the whole queue when it already fits in [maxItems].
     */
    fun windowBounds(
        targetIndex: Int,
        count: Int,
        maxItems: Int = MAX_AUXILIARY_TIMELINE_ITEMS
    ): Pair<Int, Int> {
        if (count <= maxItems) return 0 to count

        val halfWindow = maxItems / 2
        var start = (targetIndex - halfWindow).coerceAtLeast(0)
        val end = (start + maxItems).coerceAtMost(count)
        start = (end - maxItems).coerceAtLeast(0)
        return start to end
    }

    /** Ranges of the full queue missing from a windowed timeline, in splice order. */
    data class RestorePlan(
        /** Items after the window; appended first so the append indices stay valid. */
        val tail: IntRange?,
        /** Items before the window; prepended second, shifting the current index. */
        val head: IntRange?
    ) {
        val isNoOp: Boolean get() = tail == null && head == null
    }

    /**
     * Work needed to turn a window of [windowLength] items starting at [windowStart] back
     * into the full [queueSize]-item queue, or `null` when the window is not a valid slice
     * of that queue (the queue was edited while the window was in place, so the offsets can
     * no longer be trusted and splicing would duplicate or drop tracks).
     */
    fun restorePlan(windowStart: Int, windowLength: Int, queueSize: Int): RestorePlan? {
        if (queueSize < 0 || windowLength < 0) return null
        if (windowStart < 0) return null
        val windowEnd = windowStart + windowLength
        if (windowEnd > queueSize) return null

        return RestorePlan(
            tail = if (windowEnd < queueSize) windowEnd until queueSize else null,
            head = if (windowStart > 0) 0 until windowStart else null
        )
    }
}
