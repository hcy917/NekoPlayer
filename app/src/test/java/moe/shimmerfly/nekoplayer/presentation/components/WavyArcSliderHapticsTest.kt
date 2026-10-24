package moe.shimmerfly.nekoplayer.presentation.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WavyArcSliderHapticsTest {

    @Test
    fun `equalizer knob range collapses to one hundred haptic steps`() {
        val range = 0f..1000f

        assertEquals(0, wavyArcHapticStep(0f, range))
        assertEquals(100, wavyArcHapticStep(1000f, range))
        assertEquals(wavyArcHapticStep(10f, range), wavyArcHapticStep(14f, range))
        assertEquals(1, wavyArcHapticStep(14f, range))
        assertEquals(2, wavyArcHapticStep(20f, range))
    }

    @Test
    fun `range centred on zero puts zero at the middle step`() {
        val range = -15f..15f

        assertEquals(50, wavyArcHapticStep(0f, range))
        assertEquals(0, wavyArcHapticStep(-15f, range))
        assertEquals(100, wavyArcHapticStep(15f, range))
    }

    @Test
    fun `zero span range always maps to the first step`() {
        assertEquals(0, wavyArcHapticStep(5f, 5f..5f))
        assertEquals(0, wavyArcHapticStep(0f, 5f..5f))
    }
}
