package moe.shimmerfly.nekoplayer.data.media

import android.graphics.BitmapFactory
import moe.shimmerfly.nekoplayer.data.diagnostics.PerformanceMetrics
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AudioMetadataReaderTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        PerformanceMetrics.resetForTest()
        // Android's decoder is stubbed on the JVM. Report readable bounds for PNG data so
        // isValidImageData accepts the embedded cover.
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeByteArray(any(), any(), any(), any()) } answers {
            val bytes = firstArg<ByteArray>()
            val options = arg<BitmapFactory.Options>(3)
            val isPng = bytes.size >= PNG_SIGNATURE.size &&
                PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
            options.outWidth = if (isPng) 1 else -1
            options.outHeight = options.outWidth
            null
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(BitmapFactory::class)
        PerformanceMetrics.resetForTest()
    }

    @Test
    fun `normalizeArtistMetadataValues preserves repeated fields in source order`() {
        val values = normalizeArtistMetadataValues(
            listOf("Primary Artist", " Guest Artist ", "primary artist", ""),
            listOf("Another Guest")
        )

        assertEquals(
            listOf("Primary Artist", "Guest Artist", "Another Guest"),
            values
        )
    }

    @Test
    fun `JAudioTagger fallback skips embedded artwork when artwork is not requested`() {
        val file = writeTaggedWav()

        val metadata = AudioMetadataReader.readWithJAudioTagger(file, readArtwork = false)

        assertNotNull(metadata)
        assertEquals(TITLE, metadata!!.title)
        assertNull(metadata.artwork)
    }

    @Test
    fun `JAudioTagger fallback returns embedded artwork when artwork is requested`() {
        val file = writeTaggedWav()

        val metadata = AudioMetadataReader.readWithJAudioTagger(file, readArtwork = true)

        assertNotNull(metadata)
        assertEquals(TITLE, metadata!!.title)
        val artwork = metadata.artwork
        assertNotNull(artwork)
        assertArrayEquals(COVER_BYTES, artwork!!.bytes)
        assertEquals("image/png", artwork.mimeType)
    }

    @Test
    fun `read records a metadata timing even when the file cannot be read`() {
        // ParcelFileDescriptor is stubbed on the JVM, so the TagLib path fails and the read
        // returns null; the timing must still be recorded by the finally block.
        val file = File(tempDir, "unreadable.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val metadata = AudioMetadataReader.read(file, readArtwork = false)

        assertNull(metadata)
        val timing = PerformanceMetrics.snapshot().timings[PerformanceMetrics.Timings.METADATA_READ]
        assertEquals(1L, timing?.count)
        assertNull(
            PerformanceMetrics.snapshot().counters[PerformanceMetrics.Counters.METADATA_FALLBACK_JAUDIOTAGGER]
        )
    }

    private fun writeTaggedWav(): File {
        val file = File(tempDir, "poorly-tagged.wav")
        file.writeBytes(minimalPcmWav())

        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tagOrCreateAndSetDefault
        tag.setField(FieldKey.TITLE, TITLE)
        tag.setField(
            AndroidArtwork().apply {
                binaryData = COVER_BYTES
                mimeType = "image/png"
                pictureType = 3
            }
        )
        audioFile.commit()
        return file
    }

    /** A 44-byte-header, mono, 16-bit PCM WAV holding a tenth of a second of silence. */
    private fun minimalPcmWav(): ByteArray {
        val sampleRate = 8_000
        val channels = 1
        val bitsPerSample = 16
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = sampleRate / 10 * blockAlign
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * blockAlign)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()
    }

    private companion object {
        const val TITLE = "Scan Title"

        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )

        val COVER_BYTES = PNG_SIGNATURE + ByteArray(64) { it.toByte() }
    }
}
