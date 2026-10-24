package moe.shimmerfly.nekoplayer.data.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import moe.shimmerfly.nekoplayer.data.service.SleepTimerSession.Event
import moe.shimmerfly.nekoplayer.data.service.SleepTimerSession.Timer
import moe.shimmerfly.nekoplayer.data.service.player.DualPlayerEngine
import moe.shimmerfly.nekoplayer.data.service.player.DualPlayerEngine.SleepTimerHold
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerControllerTest {

    private class FakeAlarmScheduler : SleepTimerAlarmScheduler {
        val scheduledAt = mutableListOf<Long>()
        var cancelCount = 0

        override fun schedule(triggerAtMillis: Long) {
            scheduledAt += triggerAtMillis
        }

        override fun cancel() {
            cancelCount++
        }
    }

    private lateinit var player: Player
    private lateinit var engine: DualPlayerEngine
    private lateinit var session: SleepTimerSession
    private lateinit var alarmScheduler: FakeAlarmScheduler
    private val holds = mutableListOf<SleepTimerHold>()
    private val pausedAtEndListener = slot<() -> Unit>()

    /** Extra elapsed time the CPU spent asleep, on top of the scheduler's virtual clock. */
    private var sleptMillis = 0L

    private fun mediaItem(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    @BeforeEach
    fun setUp() {
        player = mockk(relaxed = true)
        engine = mockk(relaxed = true)
        session = SleepTimerSession()
        alarmScheduler = FakeAlarmScheduler()
        holds.clear()
        sleptMillis = 0L
        every { engine.setSleepTimerHold(capture(holds)) } just runs
        every { engine.addPausedAtEndOfItemListener(capture(pausedAtEndListener)) } just runs
    }

    private fun TestScope.controller(scope: CoroutineScope = backgroundScope): SleepTimerController =
        SleepTimerController(
            engine = engine,
            session = session,
            scope = scope,
            alarmScheduler = alarmScheduler,
            sessionPlayer = { player },
            elapsedRealtime = { testScheduler.currentTime + sleptMillis },
            wallClock = { WALL_NOW },
        ).also { it.attach() }

    private fun TestScope.collectEvents(): List<Event> {
        val events = mutableListOf<Event>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.events.toList(events) }
        return events
    }

    @Nested
    inner class DurationTimer {

        @Test
        fun `pauses when the countdown ends and schedules a backstop alarm`() = runTest {
            val controller = controller()

            controller.setDurationTimer(1)

            assertEquals(listOf(WALL_NOW + 60_000L), alarmScheduler.scheduledAt)
            assertEquals(Timer.Duration(WALL_NOW + 60_000L, 1), session.timer.value)
            assertTrue(holds.last().keepCpuAwake)

            advanceTimeBy(59_999L)
            runCurrent()
            verify(exactly = 0) { player.pause() }

            advanceTimeBy(2L)
            runCurrent()
            verify(exactly = 1) { player.pause() }
            assertNull(session.timer.value)
            assertEquals(SleepTimerHold(), holds.last())
        }

        @Test
        fun `counts time the CPU spent asleep`() = runTest {
            val controller = controller()
            controller.setDurationTimer(30)
            runCurrent()

            // The device slept through most of the timer: uptime barely moved.
            sleptMillis = 30 * 60_000L
            advanceTimeBy(15_001L)
            runCurrent()

            verify(exactly = 1) { player.pause() }
            assertNull(session.timer.value)
        }

        @Test
        fun `backstop alarm pauses only while a duration timer is pending`() = runTest {
            val controller = controller()
            val events = collectEvents()

            controller.onDurationExpired()
            verify(exactly = 0) { player.pause() }

            controller.setDurationTimer(10)
            controller.onDurationExpired()
            advanceTimeBy(10 * 60_000L + 1)
            runCurrent()

            verify(exactly = 1) { player.pause() }
            assertEquals(listOf(Event.DurationExpired), events)
        }

        @Test
        fun `non-positive minutes cancels instead of scheduling`() = runTest {
            val controller = controller()
            controller.setDurationTimer(5)

            controller.setDurationTimer(0)

            assertEquals(1, alarmScheduler.scheduledAt.size)
            assertNull(session.timer.value)
        }
    }

    @Nested
    inner class EndOfTrackTimer {

        @Test
        fun `arming holds playback at the end of the current item without crossfade`() = runTest {
            every { player.currentMediaItem } returns mediaItem("song-1")
            val controller = controller()
            controller.setDurationTimer(10)

            controller.setEndOfTrackTimer(true)

            assertEquals(Timer.EndOfTrack("song-1"), session.timer.value)
            assertEquals(
                SleepTimerHold(suppressCrossfade = true, pauseAtEndOfCurrentItem = true),
                holds.last()
            )
        }

        @Test
        fun `pausing at the end parks on the next song`() = runTest {
            every { player.currentMediaItem } returns mediaItem("song-1")
            every { player.repeatMode } returns Player.REPEAT_MODE_OFF
            every { player.hasNextMediaItem() } returns true
            val controller = controller()
            val events = collectEvents()
            controller.setEndOfTrackTimer(true)

            pausedAtEndListener.captured()

            verify { player.seekToNextMediaItem() }
            assertNull(session.timer.value)
            assertEquals(SleepTimerHold(), holds.last())
            assertEquals(listOf(Event.EndOfTrackReached("song-1")), events)
        }

        @Test
        fun `pausing at the end of the last song rewinds it`() = runTest {
            every { player.currentMediaItem } returns mediaItem("song-1")
            every { player.hasNextMediaItem() } returns false
            val controller = controller()
            controller.setEndOfTrackTimer(true)

            pausedAtEndListener.captured()

            verify { player.seekTo(0L) }
            verify(exactly = 0) { player.seekToNextMediaItem() }
        }

        @Test
        fun `a missed boundary pauses at the start of the next song`() = runTest {
            every { player.currentMediaItem } returns mediaItem("song-1")
            val controller = controller()
            controller.setEndOfTrackTimer(true)

            controller.onMediaItemTransition(mediaItem("song-2"), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

            verify { player.pause() }
            verify { player.seekTo(0L) }
            assertNull(session.timer.value)
        }

        @Test
        fun `a manual song change cancels the timer and reports it`() = runTest {
            every { player.currentMediaItem } returns mediaItem("song-1")
            val controller = controller()
            val events = collectEvents()
            controller.setEndOfTrackTimer(true)

            controller.onMediaItemTransition(mediaItem("song-2"), Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

            verify(exactly = 0) { player.pause() }
            assertNull(session.timer.value)
            assertEquals(listOf(Event.EndOfTrackCancelledBySongChange("song-1", "song-2")), events)
        }

        @Test
        fun `arming without an active song is ignored`() = runTest {
            every { player.currentMediaItem } returns null
            val controller = controller()

            controller.setEndOfTrackTimer(true)

            assertNull(session.timer.value)
        }
    }

    @Nested
    inner class CountedPlay {

        @BeforeEach
        fun currentSong() {
            every { player.currentMediaItem } returns mediaItem("song-1")
            every { player.repeatMode } returns Player.REPEAT_MODE_ALL
        }

        private fun repeat(controller: SleepTimerController) =
            controller.onMediaItemTransition(mediaItem("song-1"), Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)

        @Test
        fun `start loops the song with crossfade off`() = runTest {
            val controller = controller()

            controller.startCountedPlay(3)

            verify { player.repeatMode = Player.REPEAT_MODE_ONE }
            assertEquals(SleepTimerSession.CountedPlay("song-1", 3), session.countedPlay.value)
            assertEquals(SleepTimerHold(suppressCrossfade = true), holds.last())
        }

        @Test
        fun `arms the pause-at-end hold for the final play`() = runTest {
            val controller = controller()
            controller.startCountedPlay(2)

            repeat(controller)

            assertEquals(
                SleepTimerHold(suppressCrossfade = true, pauseAtEndOfCurrentItem = true),
                holds.last()
            )
        }

        @Test
        fun `finishing restores the previous repeat mode`() = runTest {
            val controller = controller()
            val events = collectEvents()
            controller.startCountedPlay(2)
            repeat(controller)

            pausedAtEndListener.captured()

            verify { player.repeatMode = Player.REPEAT_MODE_ALL }
            verify(exactly = 0) { player.repeatMode = Player.REPEAT_MODE_OFF }
            verify { player.seekTo(0L) }
            assertNull(session.countedPlay.value)
            assertEquals(SleepTimerHold(), holds.last())
            assertEquals(listOf(Event.CountedPlayFinished("song-1")), events)
        }

        @Test
        fun `a missed final boundary pauses at the start of the extra loop`() = runTest {
            val controller = controller()
            controller.startCountedPlay(1)

            repeat(controller)

            verify { player.pause() }
            verify { player.repeatMode = Player.REPEAT_MODE_ALL }
            assertNull(session.countedPlay.value)
        }

        @Test
        fun `a manual song change stops counting and restores repeat mode`() = runTest {
            val controller = controller()
            controller.startCountedPlay(5)

            controller.onMediaItemTransition(mediaItem("song-2"), Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

            verify { player.repeatMode = Player.REPEAT_MODE_ALL }
            assertNull(session.countedPlay.value)
        }

        @Test
        fun `a user repeat mode change stops counting without fighting back`() = runTest {
            val controller = controller()
            controller.startCountedPlay(5)

            controller.onRepeatModeChanged(Player.REPEAT_MODE_OFF)

            verify(exactly = 0) { player.repeatMode = Player.REPEAT_MODE_ALL }
            assertNull(session.countedPlay.value)
        }

        @Test
        fun `an end-of-track timer replaces counted play`() = runTest {
            val controller = controller()
            controller.startCountedPlay(5)

            controller.setEndOfTrackTimer(true)

            assertNull(session.countedPlay.value)
            assertEquals(Timer.EndOfTrack("song-1"), session.timer.value)
        }

        @Test
        fun `start without a current item is a no-op`() = runTest {
            every { player.currentMediaItem } returns null
            val controller = controller()

            controller.startCountedPlay(3)

            assertNull(session.countedPlay.value)
            verify(exactly = 0) { player.repeatMode = any() }
        }
    }

    @Test
    fun `release clears every timer and detaches from the engine`() = runTest {
        every { player.currentMediaItem } returns mediaItem("song-1")
        val controller = controller()
        controller.setDurationTimer(10)
        controller.startCountedPlay(3)

        controller.release()

        assertNull(session.timer.value)
        assertNull(session.countedPlay.value)
        assertEquals(SleepTimerHold(), holds.last())
        verify { engine.removePausedAtEndOfItemListener(pausedAtEndListener.captured) }
    }

    private companion object {
        const val WALL_NOW = 1_000_000L
    }
}
