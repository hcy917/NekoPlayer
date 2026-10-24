package moe.shimmerfly.nekoplayer.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HiResSampleRateCapAudioProcessorTest {

    @Test
    fun queueInput_reusesDrainedPcm16BufferWithoutReturningOldSamples() {
        val processor = HiResSampleRateCapAudioProcessor()
        processor.configure(AudioFormat(384_000, 2, C.ENCODING_PCM_16BIT))
        processor.queueInput(shortBufferOf(100, 200, 300, 400))
        val first = processor.getOutput()
        assertThat(readShorts(first)).containsExactly(200, 300).inOrder()
        first.position(first.limit())
        assertThat(processor.getOutput().hasRemaining()).isFalse()

        processor.queueInput(shortBufferOf(500, 600, 700, 800))
        val second = processor.getOutput()
        assertThat(second).isSameInstanceAs(first)
        assertThat(readShorts(second)).containsExactly(600, 700).inOrder()
    }

    @Test
    fun queueInput_growsFloatBufferThenReusesItForSmallerChunks() {
        val processor = HiResSampleRateCapAudioProcessor()
        processor.configure(AudioFormat(384_000, 2, C.ENCODING_PCM_FLOAT))
        processor.queueInput(floatBufferOf(0.1f, 0.2f, 0.1f, 0.2f))
        val first = processor.getOutput()
        first.position(first.limit())

        processor.queueInput(floatBufferOf(0.3f, 0.4f, 0.3f, 0.4f, 0.5f, 0.6f, 0.5f, 0.6f))
        val grown = processor.getOutput()
        assertThat(grown).isNotSameInstanceAs(first)
        assertThat(readFloats(grown)).containsExactly(0.3f, 0.4f, 0.5f, 0.6f).inOrder()
        grown.position(grown.limit())

        processor.queueInput(floatBufferOf(0.7f, 0.8f, 0.7f, 0.8f))
        val smaller = processor.getOutput()
        assertThat(smaller).isSameInstanceAs(grown)
        assertThat(readFloats(smaller)).containsExactly(0.7f, 0.8f).inOrder()
    }

    @Test
    @Suppress("DEPRECATION")
    fun flush_discardsPendingFramesButRetainsStorageUntilReset() {
        val processor = HiResSampleRateCapAudioProcessor()
        val format = AudioFormat(384_000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.queueInput(shortBufferOf(100, 200, 300, 400, 900, 1_000))
        val first = processor.getOutput()
        first.position(first.limit())

        processor.flush()
        assertThat(processor.getOutput().hasRemaining()).isFalse()
        assertThat(processor.isEnded()).isFalse()
        processor.queueInput(shortBufferOf(500, 600, 700, 800))
        val afterFlush = processor.getOutput()
        assertThat(afterFlush).isSameInstanceAs(first)
        assertThat(readShorts(afterFlush)).containsExactly(600, 700).inOrder()
        afterFlush.position(afterFlush.limit())

        processor.reset()
        assertThat(processor.isActive()).isFalse()
        processor.configure(format)
        processor.queueInput(shortBufferOf(900, 1_000, 1_100, 1_200))
        val afterReset = processor.getOutput()
        assertThat(afterReset).isNotSameInstanceAs(first)
        assertThat(readShorts(afterReset)).containsExactly(1_000, 1_100).inOrder()
    }

    @Test
    fun queueInput_retainsStorageWhileWaitingForPartialFrames() {
        val processor = HiResSampleRateCapAudioProcessor()
        processor.configure(AudioFormat(384_000, 2, C.ENCODING_PCM_16BIT))
        processor.queueInput(shortBufferOf(100, 200, 300, 400))
        val first = processor.getOutput()
        first.position(first.limit())

        processor.queueInput(shortBufferOf(500, 600))
        assertThat(processor.getOutput().hasRemaining()).isFalse()
        processor.queueInput(shortBufferOf(700, 800))
        val second = processor.getOutput()
        assertThat(second).isSameInstanceAs(first)
        assertThat(readShorts(second)).containsExactly(600, 700).inOrder()
    }

    @Test
    fun configure_keepsSupportedSampleRatesUntouched() {
        val processor = HiResSampleRateCapAudioProcessor()
        val inputFormat = AudioFormat(192_000, 2, C.ENCODING_PCM_16BIT)

        val outputFormat = processor.configure(inputFormat)

        assertThat(outputFormat).isEqualTo(inputFormat)
        assertThat(processor.isActive()).isFalse()
    }

    @Test
    fun queueInput_downsamples384KhzStereoTo192Khz() {
        val processor = HiResSampleRateCapAudioProcessor()
        val outputFormat = processor.configure(AudioFormat(384_000, 2, C.ENCODING_PCM_16BIT))

        processor.queueInput(
            shortBufferOf(
                1_000, -1_000,
                3_000, 1_000,
                5_000, -3_000,
                7_000, -1_000
            )
        )

        assertThat(processor.isActive()).isTrue()
        assertThat(outputFormat.sampleRate).isEqualTo(192_000)
        assertThat(outputFormat.channelCount).isEqualTo(2)
        assertThat(readShorts(processor.getOutput()))
            .containsExactly(2_000, 0, 6_000, -2_000)
            .inOrder()
    }

    @Test
    fun queueInput_carriesPartialFramesAcrossCalls() {
        val processor = HiResSampleRateCapAudioProcessor()
        processor.configure(AudioFormat(384_000, 2, C.ENCODING_PCM_16BIT))

        processor.queueInput(shortBufferOf(100, 200))
        assertThat(processor.getOutput().remaining()).isEqualTo(0)

        processor.queueInput(shortBufferOf(300, 400))

        assertThat(readShorts(processor.getOutput()))
            .containsExactly(200, 300)
            .inOrder()
    }

    @Test
    fun queueInput_carriesPartialFloatFramesAcrossCalls() {
        val processor = HiResSampleRateCapAudioProcessor()
        processor.configure(AudioFormat(384_000, 2, C.ENCODING_PCM_FLOAT))

        processor.queueInput(floatBufferOf(0.1f, 0.2f))
        assertThat(processor.getOutput().remaining()).isEqualTo(0)

        processor.queueInput(floatBufferOf(0.3f, 0.4f))

        val output = readFloats(processor.getOutput())
        assertThat(output).hasSize(2)
        assertThat(output[0]).isWithin(1e-6f).of(0.2f)
        assertThat(output[1]).isWithin(1e-6f).of(0.3f)
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

    private fun readShorts(buffer: ByteBuffer): List<Int> {
        val duplicate = buffer.duplicate().order(ByteOrder.nativeOrder())
        val samples = mutableListOf<Int>()
        while (duplicate.remaining() >= Short.SIZE_BYTES) {
            samples += duplicate.short.toInt()
        }
        return samples
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

    private fun readFloats(buffer: ByteBuffer): List<Float> {
        val duplicate = buffer.duplicate().order(ByteOrder.nativeOrder())
        val samples = mutableListOf<Float>()
        while (duplicate.remaining() >= Float.SIZE_BYTES) {
            samples += duplicate.float
        }
        return samples
    }
}
