package moe.shimmerfly.nekoplayer.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the crossfade queue window against the regression that shrank a ~5 000 song queue
 * to the 200-item window after a transition (and then persisted the truncated queue).
 */
class QueueWindowPolicyTest {

    private val max = QueueWindowPolicy.MAX_AUXILIARY_TIMELINE_ITEMS

    // ── windowBounds ─────────────────────────────────────────────────────────────

    @Test
    fun windowBounds_keepsTheWholeQueueWhenItAlreadyFits() {
        val (start, end) = QueueWindowPolicy.windowBounds(targetIndex = 3, count = max)

        assertThat(start).isEqualTo(0)
        assertThat(end).isEqualTo(max)
    }

    @Test
    fun windowBounds_centresOnTheTargetForALargeQueue() {
        val (start, end) = QueueWindowPolicy.windowBounds(targetIndex = 2_500, count = 5_000)

        assertThat(end - start).isEqualTo(max)
        assertThat(2_500).isIn(start until end)
    }

    @Test
    fun windowBounds_clampsToTheStartAndEndOfTheQueue() {
        val (headStart, headEnd) = QueueWindowPolicy.windowBounds(targetIndex = 0, count = 5_000)
        assertThat(headStart).isEqualTo(0)
        assertThat(headEnd).isEqualTo(max)

        val (tailStart, tailEnd) = QueueWindowPolicy.windowBounds(targetIndex = 4_999, count = 5_000)
        assertThat(tailEnd).isEqualTo(5_000)
        assertThat(tailStart).isEqualTo(5_000 - max)
    }

    // ── restorePlan ──────────────────────────────────────────────────────────────

    @Test
    fun restorePlan_rebuildsEveryItemOfALargeQueueExactlyOnce() {
        val queueSize = 5_000
        val (start, end) = QueueWindowPolicy.windowBounds(targetIndex = 2_500, count = queueSize)

        val plan = QueueWindowPolicy.restorePlan(start, end - start, queueSize)!!

        val restored = buildList {
            addAll(start until end)
            plan.tail?.let { addAll(it) }
            plan.head?.let { addAll(it) }
        }
        assertThat(restored).hasSize(queueSize)
        assertThat(restored.toSet()).isEqualTo((0 until queueSize).toSet())
    }

    @Test
    fun restorePlan_splicesTailBeforeHeadSoAppendIndicesStayValid() {
        val plan = QueueWindowPolicy.restorePlan(windowStart = 100, windowLength = 200, queueSize = 1_000)!!

        assertThat(plan.head).isEqualTo(0 until 100)
        assertThat(plan.tail).isEqualTo(300 until 1_000)
    }

    @Test
    fun restorePlan_isANoOpWhenTheWindowAlreadyIsTheWholeQueue() {
        val plan = QueueWindowPolicy.restorePlan(windowStart = 0, windowLength = 40, queueSize = 40)!!

        assertThat(plan.isNoOp).isTrue()
    }

    @Test
    fun restorePlan_onlyHasATailWhenTheWindowStartsAtTheQueueStart() {
        val plan = QueueWindowPolicy.restorePlan(windowStart = 0, windowLength = 200, queueSize = 5_000)!!

        assertThat(plan.head).isNull()
        assertThat(plan.tail).isEqualTo(200 until 5_000)
    }

    @Test
    fun restorePlan_onlyHasAHeadWhenTheWindowEndsAtTheQueueEnd() {
        val plan = QueueWindowPolicy.restorePlan(windowStart = 4_800, windowLength = 200, queueSize = 5_000)!!

        assertThat(plan.tail).isNull()
        assertThat(plan.head).isEqualTo(0 until 4_800)
    }

    @Test
    fun restorePlan_refusesAWindowThatOverflowsTheQueue() {
        // The queue was edited while the window was in place: the offsets no longer describe
        // a slice of it, and splicing would duplicate or drop tracks.
        assertThat(QueueWindowPolicy.restorePlan(windowStart = 4_900, windowLength = 200, queueSize = 5_000))
            .isNull()
    }

    @Test
    fun restorePlan_refusesNegativeOffsets() {
        assertThat(QueueWindowPolicy.restorePlan(windowStart = -1, windowLength = 10, queueSize = 100)).isNull()
        assertThat(QueueWindowPolicy.restorePlan(windowStart = 0, windowLength = -1, queueSize = 100)).isNull()
    }

    @Test
    fun restorePlan_refusesAWindowIntoAnEmptyQueue() {
        assertThat(QueueWindowPolicy.restorePlan(windowStart = 0, windowLength = 200, queueSize = 0)).isNull()
    }

    @Test
    fun restorePlan_restoresEveryWindowPositionAcrossALargeQueue() {
        val queueSize = 5_000
        for (target in 0 until queueSize step 137) {
            val (start, end) = QueueWindowPolicy.windowBounds(target, queueSize)
            val plan = QueueWindowPolicy.restorePlan(start, end - start, queueSize)

            assertThat(plan).isNotNull()
            val restoredCount = (end - start) +
                (plan!!.tail?.count() ?: 0) +
                (plan.head?.count() ?: 0)
            assertThat(restoredCount).isEqualTo(queueSize)
        }
    }
}
