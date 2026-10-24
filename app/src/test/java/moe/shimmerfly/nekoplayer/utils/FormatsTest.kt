package moe.shimmerfly.nekoplayer.utils

import android.content.res.Resources
import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.R
import moe.shimmerfly.nekoplayer.data.model.Song
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FormatsTest {

    private val strings = mapOf(
        R.string.duration_hours_minutes_long to "%1\$d h %2\$02d min",
        R.string.duration_minutes_long to "%1\$d min",
        R.string.duration_hours_minutes_medium to "%1\$d h %2\$02d m",
        R.string.duration_hours_medium to "%1\$d h",
        R.string.duration_minutes_medium to "%1\$d m",
        R.string.duration_seconds_medium to "%1\$d s",
        R.string.duration_hours_minutes_short to "%1\$dh %2\$02dm",
        R.string.duration_hours_short to "%1\$dh",
        R.string.duration_minutes_short to "%1\$dm",
        R.string.duration_seconds_short to "%1\$ds",
        R.string.time_ago_never to "Never",
        R.string.time_ago_just_now to "Just now",
    )

    /** English one/other patterns; the quantity picks the form the way Android does for en. */
    private val plurals = mapOf(
        R.plurals.song_count to ("%d Song" to "%d Songs"),
        R.plurals.time_ago_minutes to ("%d minute ago" to "%d minutes ago"),
        R.plurals.time_ago_hours to ("%d hour ago" to "%d hours ago"),
        R.plurals.time_ago_days to ("%d day ago" to "%d days ago"),
    )

    /** Every getQuantityString call as (id, quantity, format args). */
    private val quantityCalls = mutableListOf<Triple<Int, Int, List<Any?>>>()

    private val resources = mockk<Resources> {
        every { getString(any()) } answers { strings.getValue(firstArg()) }
        every { getString(any(), *anyVararg()) } answers {
            String.format(Locale.US, strings.getValue(firstArg()), *formatArgs(args.drop(1)))
        }
        every { getQuantityString(any(), any(), *anyVararg()) } answers {
            val id = firstArg<Int>()
            val quantity = secondArg<Int>()
            val formatArgs = formatArgs(args.drop(2))
            quantityCalls += Triple(id, quantity, formatArgs.toList())
            val (one, other) = plurals.getValue(id)
            String.format(Locale.US, if (quantity == 1) one else other, *formatArgs)
        }
    }

    /** MockK may hand a vararg over either as one array argument or already spread. */
    private fun formatArgs(rest: List<Any?>): Array<Any?> {
        val single = rest.singleOrNull()
        return if (single is Array<*>) arrayOf(*single) else rest.toTypedArray()
    }

    @Nested
    inner class TimeAgo {
        private val now = 1_700_000_000_000L

        private fun ago(millis: Long) = formatTimeAgo(resources, now - millis, nowMs = now)

        @Test
        fun `a missing timestamp reads as never`() {
            assertThat(formatTimeAgo(resources, 0L, nowMs = now)).isEqualTo("Never")
            assertThat(formatTimeAgo(resources, -1L, nowMs = now)).isEqualTo("Never")
        }

        @Test
        fun `less than a minute ago reads as just now`() {
            assertThat(ago(0L)).isEqualTo("Just now")
            assertThat(ago(59_999L)).isEqualTo("Just now")
        }

        @Test
        fun `a timestamp in the future reads as just now instead of a negative count`() {
            assertThat(ago(-5 * MINUTE)).isEqualTo("Just now")
            assertThat(ago(-3 * DAY)).isEqualTo("Just now")
            assertThat(quantityCalls).isEmpty()
        }

        @Test
        fun `minutes use the minutes plural with the count as quantity`() {
            assertThat(ago(MINUTE)).isEqualTo("1 minute ago")
            assertThat(ago(5 * MINUTE + 30_000L)).isEqualTo("5 minutes ago")
            assertThat(quantityCalls).containsExactly(
                Triple(R.plurals.time_ago_minutes, 1, listOf<Any?>(1)),
                Triple(R.plurals.time_ago_minutes, 5, listOf<Any?>(5)),
            ).inOrder()
        }

        @Test
        fun `hours use the hours plural with the count as quantity`() {
            assertThat(ago(HOUR)).isEqualTo("1 hour ago")
            assertThat(ago(3 * HOUR + 59 * MINUTE)).isEqualTo("3 hours ago")
            assertThat(quantityCalls.map { it.first to it.second }).containsExactly(
                R.plurals.time_ago_hours to 1,
                R.plurals.time_ago_hours to 3,
            ).inOrder()
        }

        @Test
        fun `days use the days plural with the count as quantity`() {
            assertThat(ago(DAY)).isEqualTo("1 day ago")
            assertThat(ago(10 * DAY + 23 * HOUR)).isEqualTo("10 days ago")
            assertThat(quantityCalls.map { it.first to it.second }).containsExactly(
                R.plurals.time_ago_days to 1,
                R.plurals.time_ago_days to 10,
            ).inOrder()
        }

        @Test
        fun `a count too large for an Int is clamped instead of overflowing`() {
            formatTimeAgo(resources, 1L, nowMs = Long.MAX_VALUE)

            assertThat(quantityCalls.single().second).isEqualTo(Int.MAX_VALUE)
        }
    }

    @Nested
    inner class ListeningDurations {
        @Test
        fun `long format covers hours and minutes, hours, minutes and seconds`() {
            assertThat(formatListeningDurationLong(resources, 3_660_000L)).isEqualTo("1 h 01 m")
            assertThat(formatListeningDurationLong(resources, 3_600_000L)).isEqualTo("1 h")
            assertThat(formatListeningDurationLong(resources, 60_000L)).isEqualTo("1 m")
            assertThat(formatListeningDurationLong(resources, 59_000L)).isEqualTo("59 s")
        }

        @Test
        fun `compact format covers hours and minutes, hours, minutes and seconds`() {
            assertThat(formatListeningDurationCompact(resources, 3_660_000L)).isEqualTo("1h 01m")
            assertThat(formatListeningDurationCompact(resources, 3_600_000L)).isEqualTo("1h")
            assertThat(formatListeningDurationCompact(resources, 60_000L)).isEqualTo("1m")
            assertThat(formatListeningDurationCompact(resources, 59_000L)).isEqualTo("59s")
        }

        @Test
        fun `total duration sums the songs and uses the long hour and minute forms`() {
            fun songs(vararg durations: Long) = durations.mapIndexed { index, duration ->
                Song.emptySong().copy(id = index.toString(), duration = duration)
            }

            assertThat(formatTotalDuration(resources, songs(1_800_000L, 1_860_000L)))
                .isEqualTo("1 h 01 min")
            assertThat(formatTotalDuration(resources, songs(3_600_000L))).isEqualTo("1 h 00 min")
            assertThat(formatTotalDuration(resources, songs(120_000L, 60_000L))).isEqualTo("3 min")
            assertThat(formatTotalDuration(resources, emptyList())).isEqualTo("0 min")
        }
    }

    @Nested
    inner class SongCount {
        @Test
        fun `the count is passed as both quantity and format argument`() {
            assertThat(formatSongCount(resources, 0)).isEqualTo("0 Songs")
            assertThat(formatSongCount(resources, 1)).isEqualTo("1 Song")
            assertThat(formatSongCount(resources, 2)).isEqualTo("2 Songs")

            assertThat(quantityCalls).containsExactly(
                Triple(R.plurals.song_count, 0, listOf<Any?>(0)),
                Triple(R.plurals.song_count, 1, listOf<Any?>(1)),
                Triple(R.plurals.song_count, 2, listOf<Any?>(2)),
            ).inOrder()
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
