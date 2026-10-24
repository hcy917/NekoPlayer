package moe.shimmerfly.nekoplayer.data.media

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Properties

private const val TAG = "SongMetadataWriteBack"

/** Headroom on top of the size growth that a truncating write-back needs before it may start. */
internal const val WRITE_BACK_SPACE_MARGIN_BYTES = 1L shl 20

/** Writing the same bytes again is idempotent, so a transient failure gets one more try. */
internal const val WRITE_BACK_ATTEMPTS = 2

/**
 * How long the song of a pending write-back may stay missing from its path (deleted or moved)
 * before its copy is dropped, counted from when it was first found missing and started over
 * whenever it is found again or cannot be checked. A song on an unmounted volume, such as a
 * removed SD card, cannot be checked, so it is never counted as missing. A song that is still
 * there is never given up on, however long it stays cut off.
 */
internal const val MISSING_SONG_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000

private const val COMPARE_BUFFER_BYTES = 64 * 1024

/** The song a write-back overwrites: its MediaStore id and the file path it had at edit time. */
internal data class WriteBackTarget(val songId: Long, val path: String)

/** Length and modification time of a song file; writing to the file changes the latter. */
internal data class SongFileStat(val length: Long, val lastModifiedMillis: Long)

/**
 * An edited copy staged by [writeBackEditedCopy] whose write into [target] has not been confirmed.
 * [copy] holds the complete edited audio; [record] names its song and keeps what the last resume
 * attempt learned about it.
 */
internal data class PendingWriteBack(
    val target: WriteBackTarget,
    val copy: File,
    val record: File,
    val createdAtMillis: Long,
    /**
     * The song's stat when it was last found to be a cut-off prefix of [copy] that could not be
     * written. While the song keeps this stat it is not read and compared again.
     */
    val cutOffStat: SongFileStat? = null,
    /** When the song was first found missing; null while it is there. */
    val missingSinceMillis: Long? = null
)

internal sealed interface WriteBackResult {
    /** Every edited byte reached the destination. */
    data object Written : WriteBackResult

    /** The destination was never opened for writing, so the original is unchanged. */
    data object NotStarted : WriteBackResult

    /**
     * The destination was truncated but did not receive every edited byte, so the original may
     * be damaged. [pending] keeps the edited audio for [resumePendingWriteBack], or is null when
     * the write-back had no song [WriteBackTarget] to record.
     */
    data class FailedAfterTruncate(val pending: PendingWriteBack?) : WriteBackResult
}

internal enum class ResumeOutcome(val keepsCopy: Boolean) {
    /** The song was cut off and now holds the edited copy again. */
    RESTORED(keepsCopy = false),

    /** The song already held the edited bytes; only the copy was left over. */
    ALREADY_WRITTEN(keepsCopy = false),

    /** The song is not a cut-off copy of the edit (never truncated, or replaced since), so it was left alone. */
    SUPERSEDED(keepsCopy = false),

    /**
     * The song is cut off but could not be opened for writing, usually because the one-off
     * write access the user granted for the edit ended with the process. The copy is kept until
     * the user grants access again.
     */
    NEEDS_WRITE_ACCESS(keepsCopy = true),

    /**
     * The song is missing, could not be read, lacks the free space, or its write failed partway;
     * the copy is kept for a later attempt.
     */
    UNAVAILABLE(keepsCopy = true),

    /** The song stayed missing past [MISSING_SONG_MAX_AGE_MILLIS], so the copy was dropped. */
    EXPIRED(keepsCopy = false)
}

/**
 * Edited copies whose truncating write-back has started but not finished, kept in [directory]
 * (under filesDir, which the system never purges). Each entry is the audio copy plus a small
 * record naming its song, so a copy left behind by a failed write-back, or by the process being
 * killed mid-write, can be written into the song again on a later launch. Not thread-safe:
 * [entries] deletes the files of a [stage] still in progress, so all access goes through
 * [PendingWriteBackGate], which serializes it.
 */
internal class PendingWriteBackStore(
    private val directory: File,
    val now: () -> Long = System::currentTimeMillis
) {

    /**
     * Moves [source] into the store as a pending write-back for [target], or copies it there if
     * it cannot be renamed. The record is written and synced before the copy appears, so an
     * entry with both files always names its song. Returns null, with [source] left in place,
     * if the copy could not be kept durably.
     */
    fun stage(source: File, target: WriteBackTarget): PendingWriteBack? {
        if (!source.isFile) return null
        if (!directory.isDirectory && !directory.mkdirs()) {
            Timber.tag(TAG).e("Could not create ${directory.path} for a pending write-back")
            return null
        }
        val baseName = "${target.songId}_${System.nanoTime()}"
        val pending = PendingWriteBack(
            target = target,
            copy = File(directory, "$baseName.$COPY_EXTENSION"),
            record = File(directory, "$baseName.$RECORD_EXTENSION"),
            createdAtMillis = now()
        )
        return try {
            writeRecord(pending.record, pending, copyLength = source.length())
            if (!source.renameTo(pending.copy)) {
                source.inputStream().use { input ->
                    FileOutputStream(pending.copy).use { out -> input.copyTo(out) }
                }
            }
            // The edit was written without syncing, and this copy must outlive a crash once the
            // original is truncated.
            RandomAccessFile(pending.copy, "rw").use { it.fd.sync() }
            pending
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Could not stage the edited copy ${source.path} for ${target.path}")
            if (pending.copy.exists() && !source.exists()) pending.copy.renameTo(source)
            pending.copy.delete()
            pending.record.delete()
            null
        }
    }

    /**
     * Every complete pending write-back, oldest first. Leftovers of a [stage], [update] or
     * [remove] that was interrupted (a record without its full copy, a copy without a record, a
     * half-written record update) are deleted: staging finishes before any song is truncated,
     * and an update replaces its record in one rename, so they never hold the only full audio.
     */
    fun entries(): List<PendingWriteBack> {
        val files = directory.listFiles() ?: return emptyList()
        val entries = files
            .filter { it.isFile && it.extension == RECORD_EXTENSION }
            .mapNotNull { record ->
                readEntry(record) ?: run {
                    Timber.tag(TAG).w("Dropping incomplete pending write-back ${record.name}")
                    File(directory, "${record.nameWithoutExtension}.$COPY_EXTENSION").delete()
                    record.delete()
                    null
                }
            }
        val kept = entries.flatMapTo(HashSet()) { listOf(it.copy, it.record) }
        files.filter { it !in kept && it.exists() }.forEach { stray ->
            Timber.tag(TAG).w("Deleting stray file ${stray.name} without a pending write-back record")
            stray.deleteRecursively()
        }
        return entries.sortedBy { it.createdAtMillis }
    }

    /**
     * Saves what [pending] now knows about its song. The record is replaced in one rename, so a
     * crash leaves either the old or the new one. Returns false, with the old record kept, if
     * it could not be saved; the entry then only forgets what this update learned.
     */
    fun update(pending: PendingWriteBack): Boolean {
        val temp = File(directory, "${pending.record.name}.$TEMP_EXTENSION")
        return try {
            writeRecord(temp, pending, copyLength = pending.copy.length())
            if (!temp.renameTo(pending.record)) throw IOException("Could not replace ${pending.record.name}")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not update pending write-back ${pending.record.name}")
            temp.delete()
            false
        }
    }

    fun remove(pending: PendingWriteBack) {
        if (pending.copy.exists() && !pending.copy.delete()) {
            Timber.tag(TAG).w("Could not delete pending copy ${pending.copy.path}")
        }
        if (pending.record.exists() && !pending.record.delete()) {
            Timber.tag(TAG).w("Could not delete pending record ${pending.record.path}")
        }
    }

    private fun writeRecord(file: File, pending: PendingWriteBack, copyLength: Long) {
        val properties = Properties().apply {
            setProperty(KEY_SONG_ID, pending.target.songId.toString())
            setProperty(KEY_PATH, pending.target.path)
            setProperty(KEY_COPY_LENGTH, copyLength.toString())
            setProperty(KEY_CREATED_AT, pending.createdAtMillis.toString())
            pending.cutOffStat?.let { stat ->
                setProperty(KEY_CUT_OFF_LENGTH, stat.length.toString())
                setProperty(KEY_CUT_OFF_MODIFIED, stat.lastModifiedMillis.toString())
            }
            pending.missingSinceMillis?.let { setProperty(KEY_MISSING_SINCE, it.toString()) }
        }
        FileOutputStream(file).use { out ->
            properties.store(out, null)
            out.fd.sync()
        }
    }

    private fun readEntry(record: File): PendingWriteBack? {
        val properties = try {
            Properties().apply { record.inputStream().use { load(it) } }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unreadable pending write-back record ${record.name}")
            return null
        }
        val songId = properties.getProperty(KEY_SONG_ID)?.toLongOrNull() ?: return null
        val path = properties.getProperty(KEY_PATH)?.takeIf { it.isNotBlank() } ?: return null
        val copyLength = properties.getProperty(KEY_COPY_LENGTH)?.toLongOrNull() ?: return null
        val createdAt = properties.getProperty(KEY_CREATED_AT)?.toLongOrNull() ?: return null
        val copy = File(directory, "${record.nameWithoutExtension}.$COPY_EXTENSION")
        if (!copy.isFile || copy.length() != copyLength) return null
        val cutOffLength = properties.getProperty(KEY_CUT_OFF_LENGTH)?.toLongOrNull()
        val cutOffModified = properties.getProperty(KEY_CUT_OFF_MODIFIED)?.toLongOrNull()
        return PendingWriteBack(
            target = WriteBackTarget(songId, path),
            copy = copy,
            record = record,
            createdAtMillis = createdAt,
            cutOffStat = if (cutOffLength != null && cutOffModified != null) {
                SongFileStat(cutOffLength, cutOffModified)
            } else {
                null
            },
            missingSinceMillis = properties.getProperty(KEY_MISSING_SINCE)?.toLongOrNull()
        )
    }

    private companion object {
        const val COPY_EXTENSION = "audio"
        const val RECORD_EXTENSION = "pending"
        const val TEMP_EXTENSION = "tmp"
        const val KEY_SONG_ID = "songId"
        const val KEY_PATH = "path"
        const val KEY_COPY_LENGTH = "copyLength"
        const val KEY_CREATED_AT = "createdAt"
        const val KEY_CUT_OFF_LENGTH = "cutOffLength"
        const val KEY_CUT_OFF_MODIFIED = "cutOffModified"
        const val KEY_MISSING_SINCE = "missingSince"
    }
}

/** How a pending write-back reaches its song. */
internal interface PendingWriteBackSong {
    /**
     * False only when the song is known to be gone from its path: its volume is mounted and the
     * library readable, yet neither the File API nor MediaStore finds it there.
     */
    fun exists(): Boolean

    /** The song file's stat, or null when it cannot be read through the File API. */
    fun stat(): SongFileStat?

    /** Opens the song for reading, or returns null / throws when it cannot be read now. */
    fun openForReading(): InputStream?

    /** Free bytes on the song's volume, or null when unknown. */
    fun availableBytes(): Long?

    /** Opens the song truncated, or returns null / throws when it cannot be written now. */
    fun openTruncated(): OutputStream?
}

/** A MediaStore row: its id and the [handle] (a content URI) it is opened through. */
internal data class MediaStoreRow<R : Any>(val id: Long, val handle: R)

/**
 * A song reached only through its path: [file] itself when the File API can read or write it,
 * otherwise the MediaStore row that [findRowNamingPath] finds for that path. A song id is never
 * used to reach it, because MediaStore can give an id to a different file after its database is
 * rebuilt, and reading one file but writing another would overwrite an unrelated song. With an
 * [expectedRowId], a row naming the path under another id is not used, so nothing is read or
 * written through it. While [canTellIfMissing] is false (no read access to the library, or the
 * path's volume is not mounted, which hides both the file and its MediaStore row), [exists]
 * never reports the song gone.
 */
internal class PathResolvedSong<R : Any>(
    private val file: File,
    private val expectedRowId: Long? = null,
    private val canTellIfMissing: () -> Boolean = { true },
    findRowNamingPath: () -> MediaStoreRow<R>?,
    private val openRow: (R) -> InputStream?,
    private val openRowTruncated: (R) -> OutputStream?,
    private val freeBytes: () -> Long?,
    private val canReadFile: () -> Boolean = file::canRead,
    private val canWriteFile: () -> Boolean = file::canWrite
) : PendingWriteBackSong {

    /** The row to open the song through, or null when no usable row names its path. */
    val row: R? by lazy {
        val found = findRowNamingPath() ?: return@lazy null
        if (expectedRowId != null && found.id != expectedRowId) {
            Timber.tag(TAG).w(
                "MediaStore row ${found.id} names ${file.path}, not song $expectedRowId; not writing through it"
            )
            null
        } else {
            found.handle
        }
    }

    override fun exists(): Boolean = file.exists() || !canTellIfMissing() || row != null

    override fun stat(): SongFileStat? =
        if (file.isFile) SongFileStat(file.length(), file.lastModified()) else null

    override fun openForReading(): InputStream? =
        if (canReadFile()) FileInputStream(file) else row?.let(openRow)

    override fun availableBytes(): Long? = freeBytes()

    override fun openTruncated(): OutputStream? =
        if (canWriteFile()) FileOutputStream(file, false) else row?.let(openRowTruncated)
}

/**
 * Overwrites a song with the edited bytes in [source]. [openDestination] must open the
 * destination truncated (or return null if it cannot), so the original is gone as soon as it
 * returns a stream. Because of that, this refuses to start when [availableBytes] (null when
 * unknown) plus the [originalLength] freed by truncation cannot hold [source], and retries a
 * failed copy.
 *
 * With a [target], [source] is first staged in [store] and the destination is opened only
 * once that succeeded, so a complete copy of the edit survives a failed copy or the process
 * dying mid-write; the entry is removed once the destination is written or was never opened,
 * and is kept (see [resumePendingWriteBack]) if the destination was truncated but not written.
 * Staging moves [source] into [store]. Without a [target] nothing is staged.
 */
internal fun writeBackEditedCopy(
    source: File,
    target: WriteBackTarget?,
    store: PendingWriteBackStore,
    originalLength: Long,
    availableBytes: Long?,
    openDestination: () -> OutputStream?
): WriteBackResult {
    if (!hasRoomToRewrite(source.length(), availableBytes, originalLength)) {
        Timber.tag(TAG).e(
            "Not overwriting the original: $availableBytes free + $originalLength freed cannot hold ${source.length()}"
        )
        return WriteBackResult.NotStarted
    }
    val pending = if (target != null) {
        store.stage(source, target) ?: run {
            Timber.tag(TAG).e("Edited copy ${source.path} could not be kept; original left untouched")
            return WriteBackResult.NotStarted
        }
    } else {
        null
    }
    return when (copyIntoDestination(pending?.copy ?: source, openDestination)) {
        CopyOutcome.WRITTEN -> {
            pending?.let(store::remove)
            WriteBackResult.Written
        }
        CopyOutcome.NOT_OPENED -> {
            pending?.let(store::remove)
            WriteBackResult.NotStarted
        }
        CopyOutcome.FAILED_AFTER_TRUNCATE -> {
            Timber.tag(TAG).e(
                "Write-back failed after truncating the original; edited copy kept at ${pending?.copy?.path}"
            )
            WriteBackResult.FailedAfterTruncate(pending)
        }
    }
}

/**
 * Finishes a write-back that [writeBackEditedCopy] left pending. The song is overwritten with the
 * edited copy only when it is a cut-off prefix of it, which is what a truncating write that
 * stopped partway leaves behind; a song that is anything else was never truncated or was
 * replaced since, and is left alone. Once the song has been found cut off, its stat is recorded,
 * and later attempts skip reading it while it keeps that stat, so a song that stays unwritable
 * (no write access, too little space) costs one failed open per attempt instead of a full read.
 *
 * The copy is dropped once the song holds it or no longer needs it, or after the song stayed
 * missing for [MISSING_SONG_MAX_AGE_MILLIS]; it is never dropped while the song is still there
 * and cut off. A missing time that started before the entry was created or after now was taken
 * from a clock that was wrong (behind at boot, or set back), so it starts over instead.
 */
internal fun resumePendingWriteBack(
    pending: PendingWriteBack,
    store: PendingWriteBackStore,
    song: PendingWriteBackSong
): ResumeOutcome {
    fun keep(updated: PendingWriteBack, outcome: ResumeOutcome): ResumeOutcome {
        if (updated != pending) store.update(updated)
        return outcome
    }

    fun drop(outcome: ResumeOutcome): ResumeOutcome {
        store.remove(pending)
        return outcome
    }

    if (!song.exists()) {
        val now = store.now()
        val missingSince = pending.missingSinceMillis?.takeIf { it in pending.createdAtMillis..now } ?: now
        if (now - missingSince > MISSING_SONG_MAX_AGE_MILLIS) {
            Timber.tag(TAG).w("Dropping the edited copy for ${pending.target.path}; the song has been gone too long")
            return drop(ResumeOutcome.EXPIRED)
        }
        return keep(pending.copy(missingSinceMillis = missingSince, cutOffStat = null), ResumeOutcome.UNAVAILABLE)
    }
    val present = pending.copy(missingSinceMillis = null)
    val stat = song.stat()
    if (stat == null || stat != present.cutOffStat) {
        val state = try {
            song.openForReading()?.use { current -> compareWithCopy(current, pending.copy) }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read ${pending.target.path} to resume its write-back")
            null
        }
        when (state) {
            null -> return keep(present.copy(cutOffStat = null), ResumeOutcome.UNAVAILABLE)
            SongState.SAME -> return drop(ResumeOutcome.ALREADY_WRITTEN)
            SongState.DIFFERENT -> return drop(ResumeOutcome.SUPERSEDED)
            SongState.CUT_OFF -> Unit
        }
    }
    val cutOff = present.copy(cutOffStat = stat)
    val songLength = stat?.length ?: 0L
    if (!hasRoomToRewrite(pending.copy.length(), song.availableBytes(), songLength)) {
        Timber.tag(TAG).w("Not enough free space to restore ${pending.target.path} yet")
        return keep(cutOff, ResumeOutcome.UNAVAILABLE)
    }
    return when (copyIntoDestination(pending.copy, song::openTruncated)) {
        CopyOutcome.WRITTEN -> drop(ResumeOutcome.RESTORED)
        CopyOutcome.NOT_OPENED -> keep(cutOff, ResumeOutcome.NEEDS_WRITE_ACCESS)
        // The song was truncated again, so what was recorded about it no longer holds.
        CopyOutcome.FAILED_AFTER_TRUNCATE -> keep(present.copy(cutOffStat = null), ResumeOutcome.UNAVAILABLE)
    }
}

/** A pending write-back that a resume attempt handled, and how. */
internal data class ResumedWriteBack(val pending: PendingWriteBack, val outcome: ResumeOutcome)

/**
 * The one way in to the song files that write-backs can leave cut off. Every edit of a song runs
 * in one critical section with resuming that song's pending write-backs, and so does the pass
 * that resumes all of them: a second edit can never start on a song that the first one left
 * cut off, and no resume mistakes a write in progress for an interrupted one. [songFor] gives
 * the way to reach a pending write-back's song; [onResumed] hears about every resume attempt,
 * with whether it covered every pending song.
 */
internal class PendingWriteBackGate(
    private val store: PendingWriteBackStore,
    private val songFor: (WriteBackTarget) -> PendingWriteBackSong,
    private val onResumed: (resumed: List<ResumedWriteBack>, everySong: Boolean) -> Unit = { _, _ -> }
) {
    private val lock = Mutex()

    /** Resumes every pending write-back. */
    suspend fun resumeAll(): List<ResumedWriteBack> = lock.withLock {
        resumeLocked(everySong = true) { true }
    }

    /**
     * Resumes the pending write-backs of the song file at [path] and then runs [write], which may
     * stage write-backs of its own in [store]. When one of those pending write-backs is still
     * unfinished, [write] does not run and [refused] is returned instead: editing the cut-off
     * song would lose the copy's audio. Entries are matched by path alone, the only way they
     * reach their song: one whose old song id MediaStore has since given to another file has
     * nothing to do with that file, and must not keep it from being edited.
     */
    suspend fun <T> writeSong(path: String, refused: () -> T, write: suspend () -> T): T =
        lock.withLock {
            val resumed = resumeLocked(everySong = false) { it.target.path == path }
            if (resumed.any { it.outcome.keepsCopy }) refused() else write()
        }

    private fun resumeLocked(everySong: Boolean, matches: (PendingWriteBack) -> Boolean): List<ResumedWriteBack> {
        val pending = try {
            store.entries().filter(matches)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Could not list pending write-backs")
            return emptyList()
        }
        val resumed = pending.map { entry ->
            val outcome = try {
                resumePendingWriteBack(entry, store, songFor(entry.target))
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Resuming the write-back for ${entry.target.path} failed")
                ResumeOutcome.UNAVAILABLE
            }
            Timber.tag(TAG).w("Pending write-back ${entry.record.name} for song ${entry.target.songId}: $outcome")
            ResumedWriteBack(entry, outcome)
        }
        if (everySong || resumed.isNotEmpty()) {
            try {
                onResumed(resumed, everySong)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Handling resumed write-backs failed")
            }
        }
        return resumed
    }
}

private fun hasRoomToRewrite(newLength: Long, availableBytes: Long?, freedByTruncation: Long): Boolean =
    availableBytes == null || availableBytes + freedByTruncation >= newLength + WRITE_BACK_SPACE_MARGIN_BYTES

private enum class CopyOutcome { WRITTEN, NOT_OPENED, FAILED_AFTER_TRUNCATE }

private fun copyIntoDestination(editedCopy: File, openDestination: () -> OutputStream?): CopyOutcome {
    // Held open across attempts, so the edited bytes stay readable for the retry.
    val input = try {
        FileInputStream(editedCopy)
    } catch (e: IOException) {
        Timber.tag(TAG).e(e, "Edited copy ${editedCopy.path} is unreadable; destination not opened")
        return CopyOutcome.NOT_OPENED
    }
    input.use {
        var truncated = false
        for (attempt in 1..WRITE_BACK_ATTEMPTS) {
            val destination = try {
                openDestination()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Write-back attempt $attempt could not open the destination")
                null
            }
            if (destination == null) {
                if (!truncated) return CopyOutcome.NOT_OPENED
                continue
            }
            truncated = true
            try {
                input.channel.position(0)
                destination.use { out ->
                    input.copyTo(out)
                    out.flush()
                    if (out is FileOutputStream) out.fd.sync()
                }
                return CopyOutcome.WRITTEN
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Write-back attempt $attempt failed after truncating the destination")
            }
        }
        return CopyOutcome.FAILED_AFTER_TRUNCATE
    }
}

private enum class SongState { SAME, CUT_OFF, DIFFERENT }

private fun compareWithCopy(current: InputStream, editedCopy: File): SongState {
    val currentBuffer = ByteArray(COMPARE_BUFFER_BYTES)
    val editedBuffer = ByteArray(COMPARE_BUFFER_BYTES)
    editedCopy.inputStream().use { edited ->
        while (true) {
            val currentRead = current.readChunk(currentBuffer)
            val editedRead = edited.readChunk(editedBuffer)
            for (i in 0 until minOf(currentRead, editedRead)) {
                if (currentBuffer[i] != editedBuffer[i]) return SongState.DIFFERENT
            }
            when {
                currentRead > editedRead -> return SongState.DIFFERENT
                currentRead < editedRead -> return SongState.CUT_OFF
                currentRead < COMPARE_BUFFER_BYTES -> return SongState.SAME
            }
        }
    }
}

/** Reads until [buffer] is full or the stream ends; returns the number of bytes read. */
private fun InputStream.readChunk(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val read = read(buffer, total, buffer.size - total)
        if (read < 0) break
        total += read
    }
    return total
}
