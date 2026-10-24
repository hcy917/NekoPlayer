package moe.shimmerfly.nekoplayer.presentation.screens

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EqualizerSliderColorTest {
    private val primary = Color(0xFF3F6AD8)
    private val inactive = Color(0xFFE2E2E9)

    @Test
    fun `transparent active colour draws no fill whether enabled or not`() {
        assertNull(resolveVerticalSliderFillColor(Color.Transparent, inactive, enabled = true))
        assertNull(resolveVerticalSliderFillColor(Color.Transparent, inactive, enabled = false))
    }

    @Test
    fun `disabled fill is blended onto the inactive track and stays opaque`() {
        val fill = resolveVerticalSliderFillColor(primary, inactive, enabled = false)

        assertNotNull(fill)
        assertEquals(1f, fill!!.alpha)
        // A 30% tint over the inactive track, not the inactive colour or the full primary.
        assertEquals(primary.red * 0.3f + inactive.red * 0.7f, fill.red, 0.01f)
        assertEquals(primary.green * 0.3f + inactive.green * 0.7f, fill.green, 0.01f)
        assertEquals(primary.blue * 0.3f + inactive.blue * 0.7f, fill.blue, 0.01f)
    }

    @Test
    fun `enabled fill uses the active colour unchanged`() {
        assertEquals(primary, resolveVerticalSliderFillColor(primary, inactive, enabled = true))
    }
}
