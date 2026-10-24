package moe.shimmerfly.nekoplayer.data.worker

import androidx.work.WorkInfo
import androidx.work.WorkRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncSchedulingPolicyTest {

    @Test
    fun `a succeeded sync with changes after its snapshot gets a follow-up`() {
        assertEquals(
            FollowUpSyncDecision.NOW,
            followUpSyncDecision(
                states = listOf(WorkInfo.State.SUCCEEDED),
                hasUnscannedChanges = true,
                consecutiveFollowUps = 0
            )
        )
        assertEquals(
            FollowUpSyncDecision.NOW,
            followUpSyncDecision(
                states = listOf(WorkInfo.State.SUCCEEDED),
                hasUnscannedChanges = true,
                consecutiveFollowUps = MAX_CONSECUTIVE_FOLLOW_UP_SYNCS - 1
            )
        )
    }

    @Test
    fun `no follow-up while sync work is still pending or running`() {
        listOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.BLOCKED
        ).forEach { pendingState ->
            assertEquals(
                FollowUpSyncDecision.NONE,
                followUpSyncDecision(
                    states = listOf(WorkInfo.State.SUCCEEDED, pendingState),
                    hasUnscannedChanges = true,
                    consecutiveFollowUps = 0
                ),
                pendingState.name
            )
        }
    }

    @Test
    fun `failed or cancelled syncs never get a follow-up`() {
        listOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED).forEach { state ->
            listOf(0, MAX_CONSECUTIVE_FOLLOW_UP_SYNCS).forEach { followUps ->
                assertEquals(
                    FollowUpSyncDecision.NONE,
                    followUpSyncDecision(
                        states = listOf(state),
                        hasUnscannedChanges = true,
                        consecutiveFollowUps = followUps
                    ),
                    "${state.name} after $followUps follow-ups"
                )
            }
        }
    }

    @Test
    fun `no follow-up without unscanned changes`() {
        listOf(0, MAX_CONSECUTIVE_FOLLOW_UP_SYNCS).forEach { followUps ->
            assertEquals(
                FollowUpSyncDecision.NONE,
                followUpSyncDecision(
                    states = listOf(WorkInfo.State.SUCCEEDED),
                    hasUnscannedChanges = false,
                    consecutiveFollowUps = followUps
                )
            )
        }
    }

    @Test
    fun `past the consecutive cap the follow-up waits for a backoff instead of being dropped`() {
        listOf(MAX_CONSECUTIVE_FOLLOW_UP_SYNCS, MAX_CONSECUTIVE_FOLLOW_UP_SYNCS + 3).forEach { followUps ->
            assertEquals(
                FollowUpSyncDecision.AFTER_BACKOFF,
                followUpSyncDecision(
                    states = listOf(WorkInfo.State.SUCCEEDED),
                    hasUnscannedChanges = true,
                    consecutiveFollowUps = followUps
                ),
                "$followUps follow-ups"
            )
        }
    }

    @Test
    fun `no follow-up when there is no sync work at all`() {
        assertEquals(
            FollowUpSyncDecision.NONE,
            followUpSyncDecision(
                states = emptyList(),
                hasUnscannedChanges = true,
                consecutiveFollowUps = 0
            )
        )
    }

    @Test
    fun `a startup sync waiting out its playback deferral is replaced`() {
        val deferred = workInfo(SyncWorker.startUpSyncWork(), WorkInfo.State.ENQUEUED, runAttemptCount = 1)
        val previous = workInfo(SyncWorker.incrementalSyncWork(), WorkInfo.State.SUCCEEDED, runAttemptCount = 1)

        assertEquals(listOf(deferred.id), playbackDeferredSyncWorkIds(listOf(deferred)))
        // Finished work left under the unique name doesn't matter.
        assertEquals(listOf(deferred.id), playbackDeferredSyncWorkIds(listOf(previous, deferred)))
    }

    @Test
    fun `running or not yet started sync work is kept`() {
        val deferrable = SyncWorker.incrementalSyncWork()
        listOf(
            workInfo(deferrable, WorkInfo.State.RUNNING, runAttemptCount = 1),
            workInfo(deferrable, WorkInfo.State.ENQUEUED, runAttemptCount = 0),
            workInfo(deferrable, WorkInfo.State.BLOCKED, runAttemptCount = 0)
        ).forEach { work ->
            assertTrue(playbackDeferredSyncWorkIds(listOf(work)).isEmpty(), work.state.name)
        }
        assertTrue(
            playbackDeferredSyncWorkIds(
                listOf(
                    workInfo(deferrable, WorkInfo.State.ENQUEUED, runAttemptCount = 2),
                    workInfo(SyncWorker.startUpSyncWork(), WorkInfo.State.RUNNING, runAttemptCount = 1)
                )
            ).isEmpty()
        )
    }

    @Test
    fun `retried work that never defers for playback is kept`() {
        // E.g. a full rescan or rebuild the system interrupted, back in ENQUEUED.
        listOf(
            SyncWorker.fullSyncWork(),
            SyncWorker.rebuildDatabaseWork(),
            SyncWorker.incrementalSyncWork(runMaintenance = false, bypassPlaybackDeferral = true)
        ).forEach { request ->
            assertTrue(
                playbackDeferredSyncWorkIds(
                    listOf(workInfo(request, WorkInfo.State.ENQUEUED, runAttemptCount = 1))
                ).isEmpty()
            )
        }
    }

    @Test
    fun `nothing to replace when no sync work is pending`() {
        assertTrue(playbackDeferredSyncWorkIds(emptyList()).isEmpty())
        assertTrue(
            playbackDeferredSyncWorkIds(
                listOf(workInfo(SyncWorker.startUpSyncWork(), WorkInfo.State.SUCCEEDED, runAttemptCount = 1))
            ).isEmpty()
        )
    }

    @Test
    fun `background incremental sync defers during playback until the deferral budget runs out`() {
        (0 until SyncWorker.MAX_PLAYBACK_DEFERRALS).forEach { attempt ->
            assertTrue(
                shouldDeferSyncForPlayback(
                    mode = SyncMode.INCREMENTAL,
                    bypass = false,
                    playbackActive = true,
                    runAttemptCount = attempt
                ),
                "attempt $attempt"
            )
        }
        assertFalse(
            shouldDeferSyncForPlayback(
                mode = SyncMode.INCREMENTAL,
                bypass = false,
                playbackActive = true,
                runAttemptCount = SyncWorker.MAX_PLAYBACK_DEFERRALS
            )
        )
    }

    @Test
    fun `user facing incremental sync runs during playback`() {
        assertFalse(
            shouldDeferSyncForPlayback(
                mode = SyncMode.INCREMENTAL,
                bypass = true,
                playbackActive = true,
                runAttemptCount = 0
            )
        )
    }

    @Test
    fun `full and rebuild syncs never defer for playback`() {
        listOf(SyncMode.FULL, SyncMode.REBUILD).forEach { mode ->
            assertFalse(
                shouldDeferSyncForPlayback(
                    mode = mode,
                    bypass = false,
                    playbackActive = true,
                    runAttemptCount = 0
                ),
                mode.name
            )
        }
    }

    @Test
    fun `incremental sync runs when nothing is playing`() {
        assertFalse(
            shouldDeferSyncForPlayback(
                mode = SyncMode.INCREMENTAL,
                bypass = false,
                playbackActive = false,
                runAttemptCount = 0
            )
        )
    }

    @Test
    fun `foreground catch-up waits for the cooldown when MediaStore did not change`() {
        assertFalse(
            shouldRunForegroundCatchUp(
                nowMs = 100_000L,
                lastCatchUpMs = 70_000L,
                cooldownMs = 60_000L,
                mediaStoreChangedWhileAway = false
            )
        )
    }

    @Test
    fun `foreground catch-up skips the cooldown when MediaStore changed while away`() {
        assertTrue(
            shouldRunForegroundCatchUp(
                nowMs = 100_000L,
                lastCatchUpMs = 70_000L,
                cooldownMs = 60_000L,
                mediaStoreChangedWhileAway = true
            )
        )
    }

    @Test
    fun `foreground catch-up runs once the cooldown has elapsed`() {
        assertTrue(
            shouldRunForegroundCatchUp(
                nowMs = 130_000L,
                lastCatchUpMs = 70_000L,
                cooldownMs = 60_000L,
                mediaStoreChangedWhileAway = false
            )
        )
        // First catch-up of the process.
        assertTrue(
            shouldRunForegroundCatchUp(
                nowMs = 100_000L,
                lastCatchUpMs = 0L,
                cooldownMs = 60_000L,
                mediaStoreChangedWhileAway = false
            )
        )
    }

    private fun workInfo(request: WorkRequest, state: WorkInfo.State, runAttemptCount: Int) = WorkInfo(
        id = request.id,
        state = state,
        tags = request.tags,
        runAttemptCount = runAttemptCount
    )
}
