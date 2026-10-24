package moe.shimmerfly.nekoplayer.data.service

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import moe.shimmerfly.nekoplayer.data.service.SleepTimerSession.CountedPlay
import moe.shimmerfly.nekoplayer.data.service.SleepTimerSession.Event
import moe.shimmerfly.nekoplayer.data.service.SleepTimerSession.Timer
import moe.shimmerfly.nekoplayer.data.service.player.DualPlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Enforces the sleep timers inside MusicService:
 *
 * - Duration: a wall-clock countdown on the service scope, with the CPU kept awake through
 *   audio offload, plus an [SleepTimerAlarmScheduler] alarm as a backstop. An inexact alarm
 *   may fire well after the requested time, so the alarm alone is not enough.
 * - End of track: a [DualPlayerEngine.SleepTimerHold] that turns crossfade off and pauses the
 *   master when the current item ends, whatever the queue, repeat or shuffle mode.
 * - Counted play: repeat-one with crossfade off (each loop is then a REPEAT transition we can
 *   count), and the same pause-at-end hold armed for the final loop.
 *
 * Every state change is published to [SleepTimerSession] so the UI can mirror it.
 */
internal class SleepTimerController(
    private val engine: DualPlayerEngine,
    private val session: SleepTimerSession,
    private val scope: CoroutineScope,
    private val alarmScheduler: SleepTimerAlarmScheduler,
    /** The MediaSession's player, so a timed pause goes through the play/pause fade. */
    private val sessionPlayer: () -> Player,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private var durationJob: Job? = null
    private var countedPlaysStarted = 0
    private var repeatModeBeforeCountedPlay: Int? = null

    private val pausedAtEndOfItemListener: () -> Unit = { onPausedAtEndOfItem() }

    fun attach() {
        engine.addPausedAtEndOfItemListener(pausedAtEndOfItemListener)
    }

    fun release() {
        engine.removePausedAtEndOfItemListener(pausedAtEndOfItemListener)
        cancelTimer()
        stopCountedPlay(restoreRepeatMode = false)
    }

    // ------------------------
    // Duration
    // ------------------------

    fun setDurationTimer(minutes: Int) {
        cancelTimer()
        if (minutes <= 0) return
        val durationMs = minutes * 60_000L
        val endsAtElapsed = elapsedRealtime() + durationMs
        val endsAtMillis = wallClock() + durationMs
        alarmScheduler.schedule(endsAtMillis)
        durationJob = scope.launch {
            // Short slices against elapsedRealtime: a single long delay runs on uptime, which
            // stands still whenever the CPU sleeps.
            while (true) {
                val remaining = endsAtElapsed - elapsedRealtime()
                if (remaining <= 0L) break
                delay(remaining.coerceAtMost(DURATION_CHECK_SLICE_MS))
            }
            onDurationExpired()
        }
        session.setTimer(Timer.Duration(endsAtMillis, minutes))
        applyEngineHold()
        Timber.tag(TAG).d("Duration sleep timer set for %d minutes", minutes)
    }

    /** The countdown or the backstop alarm ran out, whichever came first. */
    fun onDurationExpired() {
        if (session.timer.value !is Timer.Duration) return
        Timber.tag(TAG).d("Duration sleep timer expired. Pausing playback.")
        cancelTimer()
        sessionPlayer().pause()
        session.emit(Event.DurationExpired)
    }

    // ------------------------
    // End of track
    // ------------------------

    fun setEndOfTrackTimer(enabled: Boolean) {
        if (!enabled) {
            if (session.timer.value is Timer.EndOfTrack) cancelTimer()
            return
        }
        val songId = sessionPlayer().currentMediaItem?.mediaId
        if (songId.isNullOrBlank()) {
            Timber.tag(TAG).d("End-of-track timer ignored: no active song")
            return
        }
        cancelTimer()
        // Both pause at the end of the current item; the end-of-track timer wins.
        stopCountedPlay(restoreRepeatMode = true)
        session.setTimer(Timer.EndOfTrack(songId))
        applyEngineHold()
        Timber.tag(TAG).d("End-of-track timer set for mediaId=%s", songId)
    }

    fun cancelTimer() {
        durationJob?.cancel()
        durationJob = null
        alarmScheduler.cancel()
        if (session.timer.value != null) {
            session.setTimer(null)
        }
        applyEngineHold()
    }

    // ------------------------
    // Counted play
    // ------------------------

    fun startCountedPlay(count: Int) {
        val player = sessionPlayer()
        val songId = player.currentMediaItem?.mediaId ?: return
        stopCountedPlay(restoreRepeatMode = true)
        if (session.timer.value is Timer.EndOfTrack) cancelTimer()

        countedPlaysStarted = 1
        repeatModeBeforeCountedPlay = player.repeatMode
        session.setCountedPlay(CountedPlay(songId, count.coerceAtLeast(1)))
        player.repeatMode = Player.REPEAT_MODE_ONE
        applyEngineHold()
        Timber.tag(TAG).d("Counted play started: %d plays of mediaId=%s", count, songId)
    }

    fun stopCountedPlay(restoreRepeatMode: Boolean = true) {
        if (session.countedPlay.value == null) return
        val previousRepeatMode = repeatModeBeforeCountedPlay
        session.setCountedPlay(null)
        countedPlaysStarted = 0
        repeatModeBeforeCountedPlay = null
        applyEngineHold()
        if (restoreRepeatMode && previousRepeatMode != null) {
            sessionPlayer().repeatMode = previousRepeatMode
        }
    }

    // ------------------------
    // Player events (forwarded from MusicService's session-player listener)
    // ------------------------

    fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val newSongId = mediaItem?.mediaId
        val isNaturalEnd = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT

        (session.timer.value as? Timer.EndOfTrack)?.let { endOfTrack ->
            if (isNaturalEnd) {
                // The pause-at-end hold should have stopped us first; if the track still
                // rolled over, stop on the start of the next one.
                Timber.tag(TAG).w("End-of-track hold missed the boundary; pausing after it.")
                val player = sessionPlayer()
                player.pause()
                player.seekTo(0L)
                finishEndOfTrack(endOfTrack)
            } else if (newSongId != endOfTrack.songId) {
                cancelTimer()
                session.emit(Event.EndOfTrackCancelledBySongChange(endOfTrack.songId, newSongId))
            }
        }

        session.countedPlay.value?.let { countedPlay ->
            if (newSongId != countedPlay.songId) {
                stopCountedPlay(restoreRepeatMode = true)
            } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                countedPlaysStarted++
                if (countedPlaysStarted > countedPlay.total) {
                    // The hold should have paused before this loop; stop at its start instead.
                    sessionPlayer().pause()
                    finishCountedPlay(countedPlay)
                } else {
                    applyEngineHold()
                }
            }
        }
    }

    fun onRepeatModeChanged(repeatMode: Int) {
        // The user chose another repeat mode mid-count: accept it instead of fighting back.
        if (session.countedPlay.value != null && repeatMode != Player.REPEAT_MODE_ONE) {
            stopCountedPlay(restoreRepeatMode = false)
        }
    }

    fun onPlaybackEnded() {
        // The end of the queue pauses playback anyway.
        (session.timer.value as? Timer.EndOfTrack)?.let { finishEndOfTrack(it) }
    }

    private fun onPausedAtEndOfItem() {
        val endOfTrack = session.timer.value as? Timer.EndOfTrack
        if (endOfTrack != null) {
            finishEndOfTrack(endOfTrack)
            // Park on the start of what would have played next, so pressing play continues
            // the queue instead of finishing a song that already ended.
            val player = sessionPlayer()
            if (player.repeatMode != Player.REPEAT_MODE_ONE && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
            } else {
                player.seekTo(0L)
            }
            return
        }
        session.countedPlay.value?.let { countedPlay ->
            finishCountedPlay(countedPlay)
            sessionPlayer().seekTo(0L)
        }
    }

    private fun finishEndOfTrack(endOfTrack: Timer.EndOfTrack) {
        Timber.tag(TAG).d("End-of-track timer reached for mediaId=%s", endOfTrack.songId)
        cancelTimer()
        session.emit(Event.EndOfTrackReached(endOfTrack.songId))
    }

    private fun finishCountedPlay(countedPlay: CountedPlay) {
        Timber.tag(TAG).d("Counted play finished after %d plays", countedPlay.total)
        stopCountedPlay(restoreRepeatMode = true)
        session.emit(Event.CountedPlayFinished(countedPlay.songId))
    }

    private fun applyEngineHold() {
        val timer = session.timer.value
        val countedPlay = session.countedPlay.value
        val onFinalCountedPlay = countedPlay != null && countedPlaysStarted >= countedPlay.total
        engine.setSleepTimerHold(
            DualPlayerEngine.SleepTimerHold(
                suppressCrossfade = timer is Timer.EndOfTrack || countedPlay != null,
                pauseAtEndOfCurrentItem = timer is Timer.EndOfTrack || onFinalCountedPlay,
                keepCpuAwake = timer is Timer.Duration,
            )
        )
    }

    private companion object {
        const val TAG = "SleepTimerController"
        const val DURATION_CHECK_SLICE_MS = 15_000L
    }
}
