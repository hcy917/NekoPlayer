package moe.shimmerfly.nekoplayer.utils

import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Playback speed arithmetic, done in integer hundredths so stored and displayed values never
 * drift: adding 0.05f three times to 1f gives 1.1499999f, while 115 / 100f is exactly the
 * float literal 1.15f. The only float that leaves this object is `hundredths / 100f`.
 */
object PlaybackSpeedUtils {
    const val MIN_HUNDREDTHS = 25
    const val MAX_HUNDREDTHS = 300
    const val DEFAULT_HUNDREDTHS = 100
    const val FINE_STEP_HUNDREDTHS = 5

    const val MIN_SPEED = MIN_HUNDREDTHS / 100f
    const val MAX_SPEED = MAX_HUNDREDTHS / 100f
    const val DEFAULT_SPEED = DEFAULT_HUNDREDTHS / 100f

    /** Material Slider `steps` (interior stops) for one stop every 0.05× across the range. */
    const val SLIDER_STEPS = (MAX_HUNDREDTHS - MIN_HUNDREDTHS) / FINE_STEP_HUNDREDTHS - 1

    val PRESETS: List<Float> = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** Longest text the exact-speed field accepts: two digits, a separator and two decimals. */
    const val MAX_INPUT_LENGTH = 5

    private const val ARABIC_DECIMAL_SEPARATOR = '٫'
    private val INPUT_PATTERN = Regex("""^\d{0,2}([.,]\d{0,2})?$""")

    /**
     * Speed in hundredths, clamped to the supported range. Non-finite input falls back to the
     * default; clamping happens before scaling so roundToInt never sees NaN or overflows.
     */
    fun toHundredths(speed: Float): Int {
        if (!speed.isFinite()) return DEFAULT_HUNDREDTHS
        return (speed.coerceIn(MIN_SPEED, MAX_SPEED) * 100f).roundToInt()
            .coerceIn(MIN_HUNDREDTHS, MAX_HUNDREDTHS)
    }

    /** Canonical stored form: clamped and rounded to 0.01 (0.74999994f becomes 0.75f). */
    fun normalize(speed: Float): Float = toHundredths(speed) / 100f

    /** Nearest 0.05 stop, for slider values that come back as 1.1499999f and similar. */
    fun snapToFineStep(speed: Float): Float {
        if (!speed.isFinite()) return DEFAULT_SPEED
        val stepsPerUnit = 100f / FINE_STEP_HUNDREDTHS
        val ticks = (speed.coerceIn(MIN_SPEED, MAX_SPEED) * stepsPerUnit).roundToInt()
        return (ticks * FINE_STEP_HUNDREDTHS).coerceIn(MIN_HUNDREDTHS, MAX_HUNDREDTHS) / 100f
    }

    /**
     * Moves to the next 0.05 stop in [direction] (positive is faster). An off-grid value such
     * as 1.08 goes to 1.10 or 1.05 rather than to 1.13 or 1.03.
     */
    fun stepBy(speed: Float, direction: Int): Float {
        val hundredths = toHundredths(speed)
        val step = FINE_STEP_HUNDREDTHS
        val next = if (direction > 0) {
            (hundredths / step + 1) * step
        } else {
            ((hundredths + step - 1) / step - 1) * step
        }
        return next.coerceIn(MIN_HUNDREDTHS, MAX_HUNDREDTHS) / 100f
    }

    fun canStep(speed: Float, direction: Int): Boolean {
        val hundredths = toHundredths(speed)
        return if (direction > 0) hundredths < MAX_HUNDREDTHS else hundredths > MIN_HUNDREDTHS
    }

    /** Compares by hundredths, so 1.1499999f matches 1.15f. */
    fun matches(a: Float, b: Float): Boolean = toHundredths(a) == toHundredths(b)

    fun isNormal(speed: Float): Boolean = toHundredths(speed) == DEFAULT_HUNDREDTHS

    /**
     * Parses a typed speed such as "1.15", "1,15", " 1.15× ", "1.15x", ".75" or "2". Returns
     * null for blank text, NaN, Infinity, exponents, more than two decimals and values outside
     * the supported range. Non-ASCII digits and the Arabic decimal separator are accepted, so
     * any keyboard and the locale-formatted label both parse.
     */
    fun parse(input: String): Float? {
        val cleaned = input.trim().trimEnd('×', 'x', 'X').trim()
            .map { char ->
                when {
                    char == ARABIC_DECIMAL_SEPARATOR -> '.'
                    char.isDigit() -> '0' + char.digitToInt()
                    else -> char
                }
            }
            .joinToString("")
        if (cleaned.none { it in '0'..'9' } || !INPUT_PATTERN.matches(cleaned)) return null
        val hundredths = cleaned.replace(',', '.')
            .toBigDecimal()
            .setScale(2, RoundingMode.UNNECESSARY)
            .movePointRight(2)
            .intValueExact()
        return if (hundredths in MIN_HUNDREDTHS..MAX_HUNDREDTHS) hundredths / 100f else null
    }

    /**
     * Whether [text] can stand in the exact-speed field while it is being typed: at most
     * [MAX_INPUT_LENGTH] characters, digits and a single decimal separator. Range checks are
     * left to [parse] so the user can clear the field and type a new value.
     */
    fun isEditableInput(text: String): Boolean {
        if (text.length > MAX_INPUT_LENGTH) return false
        var separators = 0
        for (char in text) {
            when {
                char.isDigit() -> Unit
                char == '.' || char == ',' || char == ARABIC_DECIMAL_SEPARATOR -> separators++
                else -> return false
            }
        }
        return separators <= 1
    }

    /** Shortest form: "1", "1.5", "1.15", in ASCII digits with a decimal comma or dot. */
    fun formatCompact(speed: Float, locale: Locale = Locale.getDefault()): String {
        val hundredths = toHundredths(speed)
        val whole = hundredths / 100
        val fraction = hundredths % 100
        val separator = decimalSeparator(locale)
        return when {
            fraction == 0 -> "$whole"
            fraction % 10 == 0 -> "$whole$separator${fraction / 10}"
            else -> "$whole$separator${fraction.toString().padStart(2, '0')}"
        }
    }

    /** Always two decimals: "1.00", or "1,15" in locales that use a decimal comma. */
    fun formatFixed(speed: Float, locale: Locale = Locale.getDefault()): String {
        val hundredths = toHundredths(speed)
        val separator = decimalSeparator(locale)
        return "${hundredths / 100}$separator${(hundredths % 100).toString().padStart(2, '0')}"
    }

    /**
     * The locale's decimal comma, or a dot. Separators such as the Arabic one would sit oddly
     * next to the ASCII digits these labels use.
     */
    private fun decimalSeparator(locale: Locale): Char {
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return if (separator == ',') ',' else '.'
    }
}
