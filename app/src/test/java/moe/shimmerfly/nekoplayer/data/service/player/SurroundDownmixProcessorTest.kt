package moe.shimmerfly.nekoplayer.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SurroundDownmixProcessorTest {

    @Test
    fun queueInput_reusesDrainedPcm16BufferWithoutReturningOldSamples() {
        val processor = SurroundDownmixProcessor()
        processor.configure(AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT))
        processor.queueInput(shortBufferOf(100, 200, 0, 0, 0, 0))
        val first = processor.getOutput()
        assertThat(readShorts(first)).containsExactly(100, 200).inOrder()
        first.position(first.limit())
        assertThat(processor.getOutput().hasRemaining()).isFalse()

        processor.queueInput(shortBufferOf(300, 400, 0, 0, 0, 0))
        val second = processor.getOutput()
        assertThat(second).isSameInstanceAs(first)
        assertThat(readShorts(second)).containsExactly(300, 400).inOrder()
    }

    @Test
    fun queueInput_growsFloatBufferThenReusesItForSmallerChunks() {
        val processor = SurroundDownmixProcessor()
        processor.configure(AudioFormat(48_000, 8, C.ENCODING_PCM_FLOAT))
        processor.queueInput(floatBufferOf(0.1f, 0.2f, 0f, 0f, 0f, 0f, 0f, 0f))
        val first = processor.getOutput()
        first.position(first.limit())

        processor.queueInput(floatBufferOf(
            0.3f, 0.4f, 0f, 0f, 0f, 0f, 0f, 0f,
            0.5f, 0.6f, 0f, 0f, 0f, 0f, 0f, 0f
        ))
        val grown = processor.getOutput()
        assertThat(grown).isNotSameInstanceAs(first)
        assertThat(readFloats(grown)).containsExactly(0.3f, 0.4f, 0.5f, 0.6f).inOrder()
        grown.position(grown.limit())

        processor.queueInput(floatBufferOf(0.7f, 0.8f, 0f, 0f, 0f, 0f, 0f, 0f))
        val smaller = processor.getOutput()
        assertThat(smaller).isSameInstanceAs(grown)
        assertThat(readFloats(smaller)).containsExactly(0.7f, 0.8f).inOrder()
    }

    @Test
    @Suppress("DEPRECATION")
    fun flush_discardsPendingOutputButRetainsStorageUntilReset() {
        val processor = SurroundDownmixProcessor()
        val format = AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.queueInput(shortBufferOf(100, 200, 0, 0, 0, 0))
        val first = processor.getOutput()
        first.position(first.limit())

        processor.queueInput(shortBufferOf(300, 400, 0, 0, 0, 0))
        processor.queueEndOfStream()
        processor.flush()
        assertThat(processor.getOutput().hasRemaining()).isFalse()
        assertThat(processor.isEnded()).isFalse()
        processor.queueInput(shortBufferOf(500, 600, 0, 0, 0, 0))
        val afterFlush = processor.getOutput()
        assertThat(afterFlush).isSameInstanceAs(first)
        assertThat(readShorts(afterFlush)).containsExactly(500, 600).inOrder()
        afterFlush.position(afterFlush.limit())

        processor.reset()
        assertThat(processor.isActive()).isFalse()
        processor.configure(format)
        processor.queueInput(shortBufferOf(700, 800, 0, 0, 0, 0))
        val afterReset = processor.getOutput()
        assertThat(afterReset).isNotSameInstanceAs(first)
        assertThat(readShorts(afterReset)).containsExactly(700, 800).inOrder()
    }

    @Test
    fun queueInput_downmixes51Pcm16BitToStereo() {
        val processor = SurroundDownmixProcessor()
        val outputFormat = processor.configure(AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT))

        processor.queueInput(
            shortBufferOf(
                1_000, 2_000, 3_000, 4_000, 5_000, 6_000
            )
        )

        assertThat(processor.isActive()).isTrue()
        assertThat(outputFormat.channelCount).isEqualTo(2)
        assertThat(readShorts(processor.getOutput()))
            .containsExactly(9_484, 11_191)
            .inOrder()
    }

    @Test
    fun queueInput_downmixes71FloatToStereo() {
        val processor = SurroundDownmixProcessor()
        val outputFormat = processor.configure(AudioFormat(48_000, 8, C.ENCODING_PCM_FLOAT))

        processor.queueInput(
            floatBufferOf(
                0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f
            )
        )

        assertThat(processor.isActive()).isTrue()
        assertThat(outputFormat.channelCount).isEqualTo(2)

        val output = readFloats(processor.getOutput())
        assertThat(output).hasSize(2)
        assertThat(output[0]).isWithin(1e-6f).of(1f.coerceAtMost(0.1f + 0.707f * 0.3f + 0.707f * 0.5f + 0.707f * 0.7f + 0.707f * 0.4f))
        assertThat(output[1]).isWithin(1e-6f).of(1f.coerceAtMost(0.2f + 0.707f * 0.3f + 0.707f * 0.6f + 0.707f * 0.8f + 0.707f * 0.4f))
    }

    private fun shortBufferOf(vararg samples: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        for (sample in samples) {
            buffer.putShort(sample.toShort())
        }
        buffer.flip()
        return buffer
    }

    private fun floatBufferOf(vararg samples: Float): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        for (sample in samples) {
            buffer.putFloat(sample)
        }
        buffer.flip()
        return buffer
    }

    private fun readShorts(buffer: ByteBuffer): List<Int> {
        val duplicate = buffer.duplicate().order(ByteOrder.nativeOrder())
        val samples = mutableListOf<Int>()
        while (duplicate.remaining() >= Short.SIZE_BYTES) {
            samples += duplicate.short.toInt()
        }
        return samples
    }

    private fun readFloats(buffer: ByteBuffer): List<Float> {
        val duplicate = buffer.duplicate().order(ByteOrder.nativeOrder())
        val samples = mutableListOf<Float>()
        while (duplicate.remaining() >= Float.SIZE_BYTES) {
            samples += duplicate.float
        }
        return samples
    }
}
