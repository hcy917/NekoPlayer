package moe.shimmerfly.nekoplayer.data.media

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SongMetadataWriteBackTest {

    private lateinit var root: File
    private lateinit var source: File
    private lateinit var song: File
    private lateinit var recoveryDir: File
    private lateinit var store: PendingWriteBackStore
    private lateinit var target: WriteBackTarget
    private var nowMillis = 1_790_000_000_000L
    private val originalBytes = Random(7).nextBytes(200 * 1024)
    private val editedBytes = Random(42).nextBytes(256 * 1024)

    // A fake MediaStore: rows by id, each naming a file, writable only while the app holds a
    // write grant for that id. The File API's access to the songs is switched separately.
    private val rows = mutableMapOf<Long, File>()
    private val writeGrants = mutableSetOf<Long>()
    private var fileApiCanRead = true
    private var fileApiCanWrite = true
    private var freeBytes: Long? = null
    // False while the song's volume (an SD card) is not mounted.
    private var volumeMounted = true
    private var rowReads = 0
    private var rowWriteOpens = 0

    @BeforeEach
    fun setUp() {
        root = createTempDirectory("metadata-write-back-test").toFile()
        source = File(root, "metadata_edit_1.flac").apply { writeBytes(editedBytes) }
        song = File(root, "Music/Şarkı – ünïcode.flac").apply {
            parentFile?.mkdirs()
            writeBytes(originalBytes)
        }
        recoveryDir = File(root, "metadata_recovery")
        store = PendingWriteBackStore(recoveryDir, now = { nowMillis })
        target = WriteBackTarget(songId = 77L, path = song.path)
    }

    @AfterEach
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `failing after truncation on every attempt keeps the edited copy pending for its song`() {
        var opens = 0

        val result = writeBack {
            opens++
            FailingOutputStream(failAfterBytes = 20_000)
        }

        assertEquals(WRITE_BACK_ATTEMPTS, opens)
        val pending = checkNotNull(assertInstanceOf(WriteBackResult.FailedAfterTruncate::class.java, result).pending)
        assertEquals(listOf(pending), store.entries())
        assertEquals(target, pending.target)
        assertArrayEquals(editedBytes, pending.copy.readBytes())
    }

    @Test
    fun `the edited copy is already pending when the destination is truncated`() {
        // What a later launch would find if the process died while the song was being rewritten.
        var pendingWhenOpened: List<Pair<WriteBackTarget, ByteArray>>? = null

        writeBack {
            pendingWhenOpened = store.entries().map { it.target to it.copy.readBytes() }
            ByteArrayOutputStream()
        }

        val (pendingTarget, pendingBytes) = pendingWhenOpened.orEmpty().single()
        assertEquals(target, pendingTarget)
        assertArrayEquals(editedBytes, pendingBytes)
        assertFalse(source.exists())
    }

    @Test
    fun `a transient failure is retried with a fresh truncating open and nothing stays pending`() {
        val written = ByteArrayOutputStream()
        val destinations = ArrayDeque(listOf(FailingOutputStream(failAfterBytes = 20_000), written))

        val result = writeBack { destinations.removeFirst() }

        assertEquals(WriteBackResult.Written, result)
        assertArrayEquals(editedBytes, written.toByteArray())
        assertTrue(store.entries().isEmpty())
        assertTrue(recoveryDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a destination that cannot be opened leaves the original untouched and keeps no copy`() {
        listOf<() -> OutputStream?>(
            { throw SecurityException("no write grant") },
            { throw FileNotFoundException("gone") },
            { null }
        ).forEach { openDestination ->
            source.writeBytes(editedBytes)
            assertEquals(WriteBackResult.NotStarted, writeBack(openDestination = openDestination))
        }

        assertTrue(store.entries().isEmpty())
        assertTrue(recoveryDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `failing to reopen after a truncating attempt still keeps the edited copy pending`() {
        var opens = 0

        val result = writeBack {
            opens++
            if (opens == 1) FailingOutputStream(failAfterBytes = 0) else throw IOException("volume unmounted")
        }

        val pending = store.entries().single()
        assertEquals(WriteBackResult.FailedAfterTruncate(pending), result)
        assertArrayEquals(editedBytes, pending.copy.readBytes())
    }

    @Test
    fun `too little free space for the edited file never opens the destination or moves the copy`() {
        var opened = false

        val result = writeBack(availableBytes = 0, originalLength = editedBytes.size - 1L) {
            opened = true
            ByteArrayOutputStream()
        }

        assertEquals(WriteBackResult.NotStarted, result)
        assertFalse(opened)
        assertTrue(source.exists())
        assertFalse(recoveryDir.exists())
    }

    @Test
    fun `enough free space for the edited file lets the write-back run`() {
        val written = ByteArrayOutputStream()

        val result = writeBack(
            availableBytes = WRITE_BACK_SPACE_MARGIN_BYTES,
            originalLength = editedBytes.size.toLong()
        ) { written }

        assertEquals(WriteBackResult.Written, result)
        assertArrayEquals(editedBytes, written.toByteArray())
    }

    @Test
    fun `an unreadable edited copy never opens the destination`() {
        source.delete()
        var opened = false

        val result = writeBack {
            opened = true
            ByteArrayOutputStream()
        }

        assertEquals(WriteBackResult.NotStarted, result)
        assertFalse(opened)
    }

    @Test
    fun `without a song target a failure after truncation stages nothing`() {
        val result = writeBackEditedCopy(
            source = source,
            target = null,
            store = store,
            originalLength = editedBytes.size.toLong(),
            availableBytes = null
        ) { FailingOutputStream(failAfterBytes = 0) }

        assertEquals(WriteBackResult.FailedAfterTruncate(pending = null), result)
        assertTrue(source.exists())
        assertFalse(recoveryDir.exists())
    }

    @Test
    fun `resuming writes the edited copy into a song cut off partway and drops the copy`() {
        val pending = pendingAfterSongCutOffAt(bytes = 100_000)

        val outcome = resume(pending)

        assertEquals(ResumeOutcome.RESTORED, outcome)
        assertArrayEquals(editedBytes, song.readBytes())
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `resuming restores a song that was truncated to nothing`() {
        val pending = pendingAfterSongCutOffAt(bytes = 0)

        assertEquals(ResumeOutcome.RESTORED, resume(pending))
        assertArrayEquals(editedBytes, song.readBytes())
    }

    @Test
    fun `resuming a song that already holds the edit only drops the copy`() {
        val pending = pendingAfterSongCutOffAt(bytes = editedBytes.size)
        // Any write would have to go through the row, where it is counted.
        fileApiCanWrite = false
        rows[77L] = song
        writeGrants += 77L

        val outcome = resume(pending)

        assertEquals(ResumeOutcome.ALREADY_WRITTEN, outcome)
        assertEquals(0, rowWriteOpens)
        assertArrayEquals(editedBytes, song.readBytes())
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `resuming leaves a song alone that was never truncated or was replaced since`() {
        val pending = pendingAfterSongCutOffAt(bytes = 100_000)
        val replacement = Random(3).nextBytes(50_000)
        listOf(originalBytes, replacement).forEach { current ->
            song.writeBytes(current)
            val staged = if (pending.copy.exists()) pending else stagePending()

            val outcome = resume(staged)

            assertEquals(ResumeOutcome.SUPERSEDED, outcome)
            assertArrayEquals(current, song.readBytes())
            assertTrue(store.entries().isEmpty())
        }
    }

    @Test
    fun `an unreadable song keeps the copy pending for a later attempt`() {
        val pending = pendingAfterSongCutOffAt(bytes = 100_000)
        fileApiCanRead = false

        val outcome = resume(pending)

        assertEquals(ResumeOutcome.UNAVAILABLE, outcome)
        assertEquals(listOf(pending), store.entries())
        assertArrayEquals(editedBytes.copyOf(100_000), song.readBytes())
    }

    @Test
    fun `a song still cut off after a failed resume is recognised and restored next time`() {
        val pending = pendingAfterSongCutOffAt(bytes = 100_000)

        val failed = resume(pending) {
            object : OutputStream() {
                private val out = FileOutputStream(song, false)
                private var written = 0

                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (written + len > 30_000) throw IOException("No space left on device")
                    out.write(b, off, len)
                    written += len
                }

                override fun close() = out.close()
            }
        }

        assertEquals(ResumeOutcome.UNAVAILABLE, failed)
        val stillPending = store.entries().single()
        assertNull(stillPending.cutOffStat)
        assertEquals(ResumeOutcome.RESTORED, resume(stillPending))
        assertArrayEquals(editedBytes, song.readBytes())
    }

    @Test
    fun `a song left unwritable is read once, then retried without reading until access returns`() {
        // Scoped storage after a reboot: the song is reachable only through its MediaStore row,
        // and the write grant the edit had is gone.
        fileApiCanRead = false
        fileApiCanWrite = false
        rows[77L] = song
        val pending = pendingAfterSongCutOffAt(bytes = 100_000)
        val cutOffStat = SongFileStat(song.length(), song.lastModified())

        assertEquals(ResumeOutcome.NEEDS_WRITE_ACCESS, resume(pending))
        assertEquals(1, rowReads)
        val remembered = store.entries().single()
        assertEquals(cutOffStat, remembered.cutOffStat)

        repeat(3) {
            assertEquals(ResumeOutcome.NEEDS_WRITE_ACCESS, resume(store.entries().single()))
        }
        assertEquals(1, rowReads)
        assertEquals(4, rowWriteOpens)
        assertArrayEquals(editedBytes, store.entries().single().copy.readBytes())

        writeGrants += 77L
        assertEquals(ResumeOutcome.RESTORED, resume(store.entries().single()))
        assertEquals(1, rowReads)
        assertArrayEquals(editedBytes, song.readBytes())
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `a song changed since it was found cut off is compared again before anything is written`() {
        fileApiCanWrite = false
        val pending = pendingAfterSongCutOffAt(bytes = 100_000)
        assertEquals(ResumeOutcome.NEEDS_WRITE_ACCESS, resume(pending))
        // Another app replaces the song; its stat no longer matches what was recorded.
        val replacement = Random(5).nextBytes(120_000)
        song.writeBytes(replacement)
        song.setLastModified(song.lastModified() + 60_000)
        fileApiCanWrite = true

        assertEquals(ResumeOutcome.SUPERSEDED, resume(store.entries().single()))
        assertArrayEquals(replacement, song.readBytes())
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `a cut-off song is never given up on however long it stays unwritable or unreadable`() {
        fileApiCanWrite = false
        val pending = pendingAfterSongCutOffAt(bytes = 100_000)

        nowMillis += 365L * 24 * 60 * 60 * 1000
        assertEquals(ResumeOutcome.NEEDS_WRITE_ACCESS, resume(pending))
        fileApiCanRead = false
        song.setLastModified(song.lastModified() + 60_000)
        nowMillis += 365L * 24 * 60 * 60 * 1000
        assertEquals(ResumeOutcome.UNAVAILABLE, resume(store.entries().single()))

        val kept = store.entries().single()
        assertArrayEquals(editedBytes, kept.copy.readBytes())
        assertNull(kept.missingSinceMillis)
    }

    @Test
    fun `too little space to restore a cut-off song keeps the copy without opening the song`() {
        val pending = pendingAfterSongCutOffAt(bytes = 100_000)
        freeBytes = 0L

        assertEquals(ResumeOutcome.UNAVAILABLE, resume(pending))
        assertEquals(0, rowWriteOpens)
        assertArrayEquals(editedBytes.copyOf(100_000), song.readBytes())
        assertNotNull(store.entries().single().cutOffStat)

        freeBytes = WRITE_BACK_SPACE_MARGIN_BYTES + editedBytes.size
        assertEquals(ResumeOutcome.RESTORED, resume(store.entries().single()))
        assertArrayEquals(editedBytes, song.readBytes())
    }

    @Test
    fun `a copy is dropped only once its song stayed missing past the maximum age`() {
        val pending = pendingAfterSongCutOffAt(bytes = 100_000)
        val cutOff = song.readBytes()
        song.delete()

        assertEquals(ResumeOutcome.UNAVAILABLE, resume(pending))
        assertEquals(nowMillis, store.entries().single().missingSinceMillis)
        nowMillis += MISSING_SONG_MAX_AGE_MILLIS
        assertEquals(ResumeOutcome.UNAVAILABLE, resume(store.entries().single()))

        // The song is back at its path: the missing time starts over.
        song.writeBytes(cutOff)
        fileApiCanWrite = false
        assertEquals(ResumeOutcome.NEEDS_WRITE_ACCESS, resume(store.entries().single()))
        assertNull(store.entries().single().missingSinceMillis)

        song.delete()
        assertEquals(ResumeOutcome.UNAVAILABLE, resume(store.entries().single()))
        nowMillis += MISSING_SONG_MAX_AGE_MILLIS + 1
        assertEquals(ResumeOutcome.EXPIRED, resume(store.entries().single()))
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `a song is never counted as missing while the library cannot be seen`() {
        val pending = pendingAfterSongCutOffAt(bytes = 100_000)
        song.delete()
        nowMillis += 365L * 24 * 60 * 60 * 1000

        val outcome = resumePendingWriteBack(pending, store, songAt(target, canTellIfMissing = false))
        nowMillis += MISSING_SONG_MAX_AGE_MILLIS + 1
        val later = resumePendingWriteBack(store.entries().single(), store, songAt(target, canTellIfMissing = false))

        assertEquals(ResumeOutcome.UNAVAILABLE, outcome)
        assertEquals(ResumeOutcome.UNAVAILABLE, later)
        assertNull(store.entries().single().missingSinceMillis)
    }

    @Test
    fun `a song on a removed SD card is never counted as missing and is restored once the card is back`() {
        pendingAfterSongCutOffAt(bytes = 100_000)
        val cutOff = song.readBytes()
        // Removing the card hides its files and their MediaStore rows until it is mounted again.
        song.delete()
        volumeMounted = false

        repeat(3) {
            nowMillis += MISSING_SONG_MAX_AGE_MILLIS + 1
            assertEquals(ResumeOutcome.UNAVAILABLE, resume(store.entries().single()))
        }
        assertNull(store.entries().single().missingSinceMillis)

        song.writeBytes(cutOff)
        volumeMounted = true
        assertEquals(ResumeOutcome.RESTORED, resume(store.entries().single()))
        assertArrayEquals(editedBytes, song.readBytes())
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `a missing time taken from a wrong clock starts over instead of expiring the copy early`() {
        val pending = pendingAfterSongCutOffAt(bytes = 100_000)
        song.delete()

        // The clock is a year behind at boot when the song is first seen missing, then syncs.
        nowMillis = pending.createdAtMillis - 365 * DAY_MILLIS
        assertEquals(ResumeOutcome.UNAVAILABLE, resume(pending))
        nowMillis = pending.createdAtMillis + DAY_MILLIS
        assertEquals(ResumeOutcome.UNAVAILABLE, resume(store.entries().single()))
        assertEquals(nowMillis, store.entries().single().missingSinceMillis)

        // The clock is set back, before the time the song was first seen missing.
        nowMillis -= DAY_MILLIS / 2
        assertEquals(ResumeOutcome.UNAVAILABLE, resume(store.entries().single()))
        assertEquals(nowMillis, store.entries().single().missingSinceMillis)

        nowMillis += MISSING_SONG_MAX_AGE_MILLIS
        assertEquals(ResumeOutcome.UNAVAILABLE, resume(store.entries().single()))
        nowMillis += 1
        assertEquals(ResumeOutcome.EXPIRED, resume(store.entries().single()))
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `a pending song whose id now names another file is only read and written at its own path`() {
        // After a MediaStore rebuild, id 77 names an unrelated song and no row names the song's
        // path yet, and the File API can read the song but not write it.
        val unrelated = File(root, "Music/unrelated.flac").apply { writeBytes(originalBytes) }
        rows[77L] = unrelated
        writeGrants += 77L
        fileApiCanWrite = false
        val pending = pendingAfterSongCutOffAt(bytes = 100_000)

        assertEquals(ResumeOutcome.NEEDS_WRITE_ACCESS, resume(pending))
        assertEquals(0, rowWriteOpens)
        assertArrayEquals(originalBytes, unrelated.readBytes())
        assertArrayEquals(editedBytes.copyOf(100_000), song.readBytes())
        assertEquals(1, store.entries().size)

        // Once the song's own row is back (under a new id) and writable, it is restored there.
        rows[90L] = song
        writeGrants += 90L
        assertEquals(ResumeOutcome.RESTORED, resume(store.entries().single()))
        assertArrayEquals(editedBytes, song.readBytes())
        assertArrayEquals(originalBytes, unrelated.readBytes())
    }

    @Test
    fun `an edit writes nothing through a row that names its path under another song id`() {
        fileApiCanWrite = false
        rows[90L] = song
        writeGrants += 90L
        val destination = songAt(target, expectedRowId = target.songId)

        val result = writeBackEditedCopy(
            source = source,
            target = target,
            store = store,
            originalLength = song.length(),
            availableBytes = null,
            openDestination = destination::openTruncated
        )

        assertNull(destination.row)
        assertEquals(WriteBackResult.NotStarted, result)
        assertEquals(0, rowWriteOpens)
        assertArrayEquals(originalBytes, song.readBytes())
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `a pending write-back is read back with what it learned after the process is gone`() {
        fileApiCanWrite = false
        val pending = pendingAfterSongCutOffAt(bytes = 100_000)
        assertEquals(ResumeOutcome.NEEDS_WRITE_ACCESS, resume(pending))
        val saved = store.entries().single()

        val reloaded = PendingWriteBackStore(recoveryDir).entries().single()

        assertEquals(saved, reloaded)
        assertEquals(target, reloaded.target)
        assertEquals(SongFileStat(song.length(), song.lastModified()), reloaded.cutOffStat)
        assertEquals(pending.createdAtMillis, reloaded.createdAtMillis)
    }

    @Test
    fun `leftovers of an interrupted staging, update or removal are deleted, complete entries kept`() {
        val complete = stagePending()
        // A record whose copy never fully arrived, one whose copy is gone, a copy without a
        // record, and a record update that never replaced its record.
        stagePending().copy.apply { writeBytes(editedBytes.copyOf(1_000)) }
        stagePending().copy.delete()
        val strayCopy = File(recoveryDir, "5_123.audio").apply { writeBytes(editedBytes) }
        val legacyCopy = File(recoveryDir, "1700000000000_song.flac").apply { writeBytes(editedBytes) }
        val halfUpdate = File(recoveryDir, "${complete.record.name}.tmp").apply { writeText("songId=") }

        assertEquals(listOf(complete), store.entries())
        assertEquals(
            setOf(complete.copy.name, complete.record.name),
            recoveryDir.listFiles().orEmpty().mapTo(HashSet()) { it.name }
        )
        assertFalse(strayCopy.exists())
        assertFalse(legacyCopy.exists())
        assertFalse(halfUpdate.exists())
    }

    @Test
    fun `a second edit of the same song waits for the first and is refused when the first left it cut off`() = runTest {
        val gate = PendingWriteBackGate(store, songFor = { songAt(it) })
        val firstIsWriting = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        var secondWrote = false

        val first = async {
            gate.writeSong(target.path, refused = { "refused" }) {
                firstIsWriting.complete(Unit)
                finishFirst.await()
                // The volume fills up partway through the first edit's write-back.
                val result = writeBack { cutOffStream(acceptBytes = 100_000) }
                freeBytes = 0L
                assertInstanceOf(WriteBackResult.FailedAfterTruncate::class.java, result)
                "first"
            }
        }
        firstIsWriting.await()
        // E.g. lyrics being saved into the same song while its tag edit is still writing.
        val second = async {
            gate.writeSong(target.path, refused = { "refused" }) {
                secondWrote = true
                "second"
            }
        }
        runCurrent()
        assertFalse(secondWrote)
        finishFirst.complete(Unit)

        assertEquals("first", first.await())
        assertEquals("refused", second.await())
        assertFalse(secondWrote)
        assertArrayEquals(editedBytes.copyOf(100_000), song.readBytes())
        assertArrayEquals(editedBytes, store.entries().single().copy.readBytes())
    }

    @Test
    fun `an edit is refused only while a pending write-back of its own path cannot be finished`() = runTest {
        fileApiCanWrite = false
        pendingAfterSongCutOffAt(bytes = 100_000)
        val reported = mutableListOf<List<ResumeOutcome>>()
        val gate = PendingWriteBackGate(
            store,
            songFor = { songAt(it) },
            onResumed = { resumed, _ -> reported += resumed.map { it.outcome } }
        )
        val otherSong = File(root, "Music/other.flac").apply { writeBytes(originalBytes) }
        // After a MediaStore rebuild the cut-off song has a new id and its old id 77 names
        // otherSong, whose write grant can never cover the cut-off song's row.
        rows[90L] = song
        rows[77L] = otherSong
        writeGrants += 77L
        val written = mutableListOf<String>()

        listOf(song.path, otherSong.path).forEach { path ->
            gate.writeSong(path, refused = {}) { written += path }
        }

        // The cut-off song is refused; otherSong is edited, since the pending copy only ever
        // reaches its own path.
        assertEquals(listOf(otherSong.path), written)
        assertEquals(listOf(listOf(ResumeOutcome.NEEDS_WRITE_ACCESS)), reported)
        assertEquals(1, store.entries().size)
        assertArrayEquals(originalBytes, otherSong.readBytes())
    }

    @Test
    fun `an edit of a song with a pending copy restores it first and then runs`() = runTest {
        pendingAfterSongCutOffAt(bytes = 100_000)
        var everySongPasses = 0
        val gate = PendingWriteBackGate(
            store,
            songFor = { songAt(it) },
            onResumed = { _, everySong -> if (everySong) everySongPasses++ }
        )
        var songWhenEdited: ByteArray? = null

        gate.writeSong(target.path, refused = {}) { songWhenEdited = song.readBytes() }

        assertArrayEquals(editedBytes, songWhenEdited)
        assertTrue(store.entries().isEmpty())
        assertTrue(gate.resumeAll().isEmpty())
        assertEquals(1, everySongPasses)
    }

    private fun writeBack(
        availableBytes: Long? = null,
        originalLength: Long = editedBytes.size.toLong(),
        openDestination: () -> OutputStream?
    ): WriteBackResult = writeBackEditedCopy(
        source = source,
        target = target,
        store = store,
        originalLength = originalLength,
        availableBytes = availableBytes,
        openDestination = openDestination
    )

    /** The song at [target]'s path, through the File API or the fake MediaStore rows. */
    private fun songAt(
        target: WriteBackTarget,
        expectedRowId: Long? = null,
        canTellIfMissing: Boolean = true,
        openTruncatedFile: (() -> OutputStream?)? = null
    ): PathResolvedSong<Long> = PathResolvedSong(
        file = File(target.path),
        expectedRowId = expectedRowId,
        canTellIfMissing = { canTellIfMissing && volumeMounted },
        findRowNamingPath = {
            rows.entries.firstOrNull { it.value.path == target.path }?.let { MediaStoreRow(it.key, it.key) }
        },
        openRow = { id ->
            rowReads++
            rows.getValue(id).inputStream()
        },
        openRowTruncated = { id ->
            rowWriteOpens++
            if (id !in writeGrants) throw SecurityException("No write grant for row $id")
            openTruncatedFile?.invoke() ?: FileOutputStream(rows.getValue(id), false)
        },
        freeBytes = { freeBytes },
        canReadFile = { fileApiCanRead },
        canWriteFile = { fileApiCanWrite && openTruncatedFile == null }
    )

    private fun resume(
        pending: PendingWriteBack,
        openTruncatedFile: (() -> OutputStream?)? = null
    ): ResumeOutcome {
        if (openTruncatedFile != null) {
            // Routes the write through a granted row so the test controls the stream.
            rows[target.songId] = song
            writeGrants += target.songId
        }
        return resumePendingWriteBack(pending, store, songAt(pending.target, openTruncatedFile = openTruncatedFile))
    }

    private fun stagePending(): PendingWriteBack {
        source.writeBytes(editedBytes)
        return checkNotNull(store.stage(source, target))
    }

    /** Leaves [song] holding the first [bytes] edited bytes, as a truncating write cut short does. */
    private fun pendingAfterSongCutOffAt(bytes: Int): PendingWriteBack {
        val result = writeBack { cutOffStream(acceptBytes = bytes) }
        assertArrayEquals(editedBytes.copyOf(bytes), song.readBytes())
        if (bytes == editedBytes.size) {
            // Every byte made it, so this write-back was not interrupted; recreate its leftover
            // entry as a process killed right after the write would have left it.
            assertEquals(WriteBackResult.Written, result)
            assertTrue(store.entries().isEmpty())
            return stagePending()
        }
        return checkNotNull(assertInstanceOf(WriteBackResult.FailedAfterTruncate::class.java, result).pending)
    }

    /** Truncates [song] and accepts [acceptBytes] bytes into it, then fails like a full volume. */
    private fun cutOffStream(acceptBytes: Int): OutputStream = object : OutputStream() {
        private val out = FileOutputStream(song, false)
        private var written = 0

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val accepted = minOf(len, acceptBytes - written)
            if (accepted > 0) {
                out.write(b, off, accepted)
                written += accepted
            }
            if (accepted < len) {
                out.close()
                throw IOException("No space left on device")
            }
        }

        override fun close() = out.close()
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }

    /** Accepts [failAfterBytes] bytes, then fails like a full or unmounted volume. */
    private class FailingOutputStream(private val failAfterBytes: Int) : OutputStream() {
        private var written = 0

        override fun write(b: Int) {
            if (written >= failAfterBytes) throw IOException("No space left on device")
            written++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (written + len > failAfterBytes) throw IOException("No space left on device")
            written += len
        }
    }
}
