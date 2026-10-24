package moe.shimmerfly.nekoplayer.utils

import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Float equality here is exact on purpose: the stored speed must be the float literal the
 * label shows, so 1.15 is stored as 1.15f and never as a neighbouring float such as 1.1499999f.
 */
class PlaybackSpeedUtilsTest {

    private val turkish = Locale.forLanguageTag("tr")

    @Test
    fun `snapToFineStep lands slider noise on exact 0_05 floats`() {
        assertEquals(1.15f, PlaybackSpeedUtils.snapToFineStep(1.1499999f))
        assertEquals("1.15", PlaybackSpeedUtils.snapToFineStep(1.1499999f).toString())
        assertEquals(1.15f, PlaybackSpeedUtils.snapToFineStep(1.1500001f))
        assertEquals(1.10f, PlaybackSpeedUtils.snapToFineStep(1.12f))
        assertEquals(1.15f, PlaybackSpeedUtils.snapToFineStep(1.13f))
        assertEquals(0.25f, PlaybackSpeedUtils.snapToFineStep(0.26f))
        assertEquals(3f, PlaybackSpeedUtils.snapToFineStep(2.99f))
        assertEquals(1f, PlaybackSpeedUtils.snapToFineStep(Float.NaN))
    }

    @Test
    fun `every slider stop snaps to its decimal`() {
        val stops = PlaybackSpeedUtils.SLIDER_STEPS + 2
        for (i in 0 until stops) {
            // How the Material slider turns a stop fraction back into a value.
            val sliderValue = 0.25f + 2.75f * (i / (stops - 1).toFloat())

            assertEquals((25 + 5 * i) / 100f, PlaybackSpeedUtils.snapToFineStep(sliderValue), "stop $i")
        }
    }

    @Test
    fun `SLIDER_STEPS gives one stop per 0_05 across the range`() {
        assertEquals(54, PlaybackSpeedUtils.SLIDER_STEPS)
        assertEquals(0.25f, PlaybackSpeedUtils.MIN_SPEED)
        assertEquals(3f, PlaybackSpeedUtils.MAX_SPEED)
    }

    @Test
    fun `normalize clamps and repairs non-finite values`() {
        assertEquals(1f, PlaybackSpeedUtils.normalize(Float.NaN))
        assertEquals(1f, PlaybackSpeedUtils.normalize(Float.POSITIVE_INFINITY))
        assertEquals(1f, PlaybackSpeedUtils.normalize(Float.NEGATIVE_INFINITY))
        assertEquals(0.25f, PlaybackSpeedUtils.normalize(-1f))
        assertEquals(0.25f, PlaybackSpeedUtils.normalize(0.1f))
        assertEquals(3f, PlaybackSpeedUtils.normalize(10f))
        assertEquals(0.75f, PlaybackSpeedUtils.normalize(0.74999994f))
        assertEquals(1.08f, PlaybackSpeedUtils.normalize(1.08f))
    }

    @Test
    fun `every hundredth in range round-trips`() {
        for (hundredths in 25..300) {
            val speed = hundredths / 100f

            assertEquals(hundredths, PlaybackSpeedUtils.toHundredths(speed))
            assertEquals(speed, PlaybackSpeedUtils.normalize(speed))
        }
    }

    @Test
    fun `stepBy uses integer arithmetic instead of accumulating floats`() {
        // The naive version drifts: 1f + 0.05f three times is 1.1499999f.
        assertNotEquals(1.15f, 1f + 0.05f + 0.05f + 0.05f)

        var speed = 1f
        repeat(3) { speed = PlaybackSpeedUtils.stepBy(speed, 1) }

        assertEquals(1.15f, speed)
    }

    @Test
    fun `stepBy moves an off grid value to the next stop and clamps at the ends`() {
        assertEquals(1.10f, PlaybackSpeedUtils.stepBy(1.08f, 1))
        assertEquals(1.05f, PlaybackSpeedUtils.stepBy(1.08f, -1))
        assertEquals(1.05f, PlaybackSpeedUtils.stepBy(1.10f, -1))
        assertEquals(3f, PlaybackSpeedUtils.stepBy(3f, 1))
        assertEquals(0.25f, PlaybackSpeedUtils.stepBy(0.25f, -1))
        assertEquals(3f, PlaybackSpeedUtils.stepBy(2.98f, 1))
    }

    @Test
    fun `canStep is false at both ends of the range`() {
        assertFalse(PlaybackSpeedUtils.canStep(3f, 1))
        assertFalse(PlaybackSpeedUtils.canStep(0.25f, -1))
        assertTrue(PlaybackSpeedUtils.canStep(3f, -1))
        assertTrue(PlaybackSpeedUtils.canStep(0.25f, 1))
        assertTrue(PlaybackSpeedUtils.canStep(1f, 1))
        assertTrue(PlaybackSpeedUtils.canStep(1f, -1))
    }

    @Test
    fun `parse accepts a dot, a comma and a times suffix`() {
        listOf("1.15", "1,15", " 1.15× ", "1.15x", "1.15X", "1.15 ×").forEach { input ->
            assertEquals(1.15f, PlaybackSpeedUtils.parse(input), input)
        }
        assertEquals(0.75f, PlaybackSpeedUtils.parse(".75"))
        assertEquals(2f, PlaybackSpeedUtils.parse("2"))
        assertEquals(2f, PlaybackSpeedUtils.parse("2."))
        assertEquals(3f, PlaybackSpeedUtils.parse("3"))
        assertEquals(0.25f, PlaybackSpeedUtils.parse("0.25"))
        assertEquals(1.08f, PlaybackSpeedUtils.parse("1,08"))
    }

    @Test
    fun `parse accepts other keyboards' digits and the Arabic decimal separator`() {
        assertEquals(1.15f, PlaybackSpeedUtils.parse("١٫١٥"))
    }

    @Test
    fun `parse rejects invalid input`() {
        listOf(
            "", " ", "abc", "NaN", "Infinity", "1e0", "-1", "0.2", "3.01", "1.155", "1.1.1", ".", "×"
        ).forEach { input ->
            assertNull(PlaybackSpeedUtils.parse(input), "'$input'")
        }
    }

    @Test
    fun `isEditableInput allows digits and one separator up to five characters`() {
        assertTrue(PlaybackSpeedUtils.isEditableInput(""))
        assertTrue(PlaybackSpeedUtils.isEditableInput("1"))
        assertTrue(PlaybackSpeedUtils.isEditableInput("1,"))
        assertTrue(PlaybackSpeedUtils.isEditableInput("10.25"))
        assertTrue(PlaybackSpeedUtils.isEditableInput("5"))
        assertFalse(PlaybackSpeedUtils.isEditableInput("100.25"))
        assertFalse(PlaybackSpeedUtils.isEditableInput("1.2.3"))
        assertFalse(PlaybackSpeedUtils.isEditableInput("1,2."))
        assertFalse(PlaybackSpeedUtils.isEditableInput("-1"))
        assertFalse(PlaybackSpeedUtils.isEditableInput("1e2"))
    }

    @Test
    fun `formatCompact drops trailing zeros and uses the locale separator`() {
        assertEquals("1", PlaybackSpeedUtils.formatCompact(1f, Locale.US))
        assertEquals("1.5", PlaybackSpeedUtils.formatCompact(1.5f, Locale.US))
        assertEquals("1.15", PlaybackSpeedUtils.formatCompact(1.15f, Locale.US))
        assertEquals("1.05", PlaybackSpeedUtils.formatCompact(1.05f, Locale.US))
        assertEquals("0.75", PlaybackSpeedUtils.formatCompact(0.75f, Locale.US))
        assertEquals("1,15", PlaybackSpeedUtils.formatCompact(1.15f, turkish))
    }

    @Test
    fun `formatFixed always shows two decimals`() {
        assertEquals("1.00", PlaybackSpeedUtils.formatFixed(1f, Locale.US))
        assertEquals("0.25", PlaybackSpeedUtils.formatFixed(0.25f, Locale.US))
        assertEquals("3.00", PlaybackSpeedUtils.formatFixed(3f, Locale.US))
        assertEquals("1,15", PlaybackSpeedUtils.formatFixed(1.15f, Locale.GERMAN))
    }

    @Test
    fun `formatted labels parse back to the same speed`() {
        for (hundredths in 25..300 step 5) {
            val speed = hundredths / 100f

            assertEquals(speed, PlaybackSpeedUtils.parse(PlaybackSpeedUtils.formatFixed(speed, turkish)))
            assertEquals(speed, PlaybackSpeedUtils.parse(PlaybackSpeedUtils.formatCompact(speed, Locale.US)))
        }
    }

    @Test
    fun `matches and isNormal compare by hundredths`() {
        assertTrue(PlaybackSpeedUtils.matches(1.1499999f, 1.15f))
        assertFalse(PlaybackSpeedUtils.matches(1.14f, 1.15f))
        assertTrue(PlaybackSpeedUtils.isNormal(0.99999994f))
        assertTrue(PlaybackSpeedUtils.isNormal(Float.NaN))
        assertFalse(PlaybackSpeedUtils.isNormal(1.01f))
    }
}
