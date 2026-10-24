package moe.shimmerfly.nekoplayer.data.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalLibraryChangeTrackerTest {

    private val tracker = LocalLibraryChangeTracker()

    @Test
    fun `no follow-up is needed when nothing changed after the scan started`() {
        tracker.onLocalScanStarted()

        assertFalse(tracker.hasUnscannedChanges())
    }

    @Test
    fun `a change that arrives after the scan snapshot needs a follow-up`() {
        tracker.onLocalScanStarted()
        tracker.onMediaStoreChanged()

        assertTrue(tracker.hasUnscannedChanges())
    }

    @Test
    fun `a change that arrived before the scan snapshot is covered by that scan`() {
        tracker.onMediaStoreChanged()
        tracker.onMediaStoreChanged()
        tracker.onLocalScanStarted()

        assertFalse(tracker.hasUnscannedChanges())
    }

    @Test
    fun `a newer scan snapshot clears pending changes`() {
        tracker.onLocalScanStarted()
        tracker.onMediaStoreChanged()
        assertTrue(tracker.hasUnscannedChanges())

        tracker.onLocalScanStarted()

        assertFalse(tracker.hasUnscannedChanges())
    }

    @Test
    fun `scan snapshot never moves backwards`() {
        // A slower worker read the version before more changes arrived...
        val staleVersion = tracker.currentChangeVersion()
        repeat(3) { tracker.onMediaStoreChanged() }
        // ...while another worker already scanned all of them.
        tracker.onLocalScanStarted()

        tracker.markScannedUpTo(staleVersion)

        assertFalse(tracker.hasUnscannedChanges())
    }

    @Test
    fun `follow-up counter increments and resets`() {
        assertEquals(0, tracker.consecutiveFollowUps())

        tracker.recordFollowUp()
        tracker.recordFollowUp()
        assertEquals(2, tracker.consecutiveFollowUps())

        tracker.resetFollowUps()
        assertEquals(0, tracker.consecutiveFollowUps())
    }
}
