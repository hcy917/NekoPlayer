package moe.shimmerfly.nekoplayer.data.worker

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers whether MediaStore changed after the local library scan last read it.
 *
 * Storage-change syncs are enqueued with KEEP, so a change that lands while a sync is already
 * running would otherwise be dropped: the running scan has already read MediaStore, and the new
 * request is ignored. [SyncManager] bumps the change version on every MediaStore notification,
 * [SyncWorker] snapshots it right before reading MediaStore, and once the sync finishes any
 * change newer than that snapshot gets one follow-up scan.
 *
 * Process-local on purpose: after a process restart the foreground catch-up sync covers anything
 * that changed in between.
 */
@Singleton
class LocalLibraryChangeTracker @Inject constructor() {
    private val changeVersion = AtomicLong(0)
    private val scannedVersion = AtomicLong(0)
    private val consecutiveFollowUps = AtomicInteger(0)

    /** Records that MediaStore reported a change that a scan has not read yet. */
    fun onMediaStoreChanged() {
        changeVersion.incrementAndGet()
    }

    /**
     * Call right before the worker reads MediaStore, and on paths that skip the scan on purpose
     * (no permission), so those paths cannot cause a follow-up loop.
     */
    fun onLocalScanStarted() {
        markScannedUpTo(currentChangeVersion())
    }

    internal fun currentChangeVersion(): Long = changeVersion.get()

    /**
     * Never moves the snapshot backwards: a slower concurrent worker (the periodic maintenance
     * run uses its own unique work) can report an older version after a newer one.
     */
    internal fun markScannedUpTo(version: Long) {
        scannedVersion.accumulateAndGet(version) { current, seen -> maxOf(current, seen) }
    }

    fun hasUnscannedChanges(): Boolean = changeVersion.get() > scannedVersion.get()

    fun consecutiveFollowUps(): Int = consecutiveFollowUps.get()

    fun recordFollowUp() {
        consecutiveFollowUps.incrementAndGet()
    }

    fun resetFollowUps() {
        consecutiveFollowUps.set(0)
    }
}
