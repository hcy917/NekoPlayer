package moe.shimmerfly.nekoplayer.presentation.viewmodel

import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import moe.shimmerfly.nekoplayer.R
import moe.shimmerfly.nekoplayer.data.service.MusicNotificationProvider
import moe.shimmerfly.nekoplayer.data.service.SleepTimerSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the sleep timers for the UI and forwards the user's choices to MusicService.
 *
 * The service ([moe.shimmerfly.nekoplayer.data.service.SleepTimerController]) owns and
 * enforces every timer, since only it lives for the whole playback session and sees every
 * player swap; this holder only renders [SleepTimerSession] and toasts its events.
 */
@Singleton
class SleepTimerStateHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SleepTimerSession,
) {
    private val _isEndOfTrackTimerActive = MutableStateFlow(value = false)
    val isEndOfTrackTimerActive: StateFlow<Boolean> = _isEndOfTrackTimerActive.asStateFlow()

    private val _activeTimerValueDisplay = MutableStateFlow<String?>(null)
    val activeTimerValueDisplay: StateFlow<String?> = _activeTimerValueDisplay.asStateFlow()

    /** When non-null, a duration-based sleep timer is active with this many minutes (matches [predefinedTimes]). */
    private val _activeTimerDurationMinutes = MutableStateFlow<Int?>(null)
    val activeTimerDurationMinutes: StateFlow<Int?> = _activeTimerDurationMinutes.asStateFlow()

    private val _playCount = MutableStateFlow(1f)
    val playCount: StateFlow<Float> = _playCount.asStateFlow()

    private var sessionMirrorJob: Job? = null

    private var scope: CoroutineScope? = null
    private var toastEmitter: (suspend (String) -> Unit)? = null
    private var mediaControllerProvider: (() -> MediaController?)? = null
    private var songTitleResolver: ((String?) -> String?)? = null
    private val owners = OwnerStack<Binding> { it.scope }

    /** One PlayerViewModel's dependencies, kept so the holder can fall back to them. */
    private class Binding(
        val scope: CoroutineScope,
        val toastEmitter: suspend (String) -> Unit,
        val mediaControllerProvider: () -> MediaController?,
        val songTitleResolver: (String?) -> String?,
    )

    /**
     * Initialize with dependencies from ViewModel.
     * Must be called before using timer functions.
     */
    fun initialize(
        scope: CoroutineScope,
        toastEmitter: suspend (String) -> Unit,
        mediaControllerProvider: () -> MediaController?,
        songTitleResolver: (String?) -> String?,
    ) {
        val binding = Binding(scope, toastEmitter, mediaControllerProvider, songTitleResolver)
        owners.push(binding)
        bind(binding)
    }

    private fun bind(binding: Binding) {
        scope = binding.scope
        toastEmitter = binding.toastEmitter
        mediaControllerProvider = binding.mediaControllerProvider
        songTitleResolver = binding.songTitleResolver

        sessionMirrorJob?.cancel()
        sessionMirrorJob = binding.scope.launch {
            launch { session.timer.collect { renderTimer(it) } }
            launch {
                session.countedPlay.collect { countedPlay ->
                    _playCount.value = countedPlay?.total?.toFloat() ?: 1f
                }
            }
            launch { session.events.collect { toastFor(it) } }
        }
    }

    private fun renderTimer(timer: SleepTimerSession.Timer?) {
        _isEndOfTrackTimerActive.value = timer is SleepTimerSession.Timer.EndOfTrack
        _activeTimerDurationMinutes.value = (timer as? SleepTimerSession.Timer.Duration)?.minutes
        _activeTimerValueDisplay.value = when (timer) {
            is SleepTimerSession.Timer.Duration ->
                context.getString(R.string.sleep_timer_n_minutes_format, timer.minutes)
            is SleepTimerSession.Timer.EndOfTrack ->
                context.getString(R.string.sleep_timer_display_eot)
            null -> null
        }
    }

    private suspend fun toastFor(event: SleepTimerSession.Event) {
        val message = when (event) {
            is SleepTimerSession.Event.EndOfTrackReached -> context.getString(
                R.string.player_playback_stopped_eot,
                songTitleResolver?.invoke(event.songId)
                    ?: context.getString(R.string.player_default_track_title)
            )
            is SleepTimerSession.Event.EndOfTrackCancelledBySongChange -> context.getString(
                R.string.sleep_timer_eot_song_changed_toast,
                songTitleResolver?.invoke(event.targetSongId)
                    ?: context.getString(R.string.sleep_timer_label_previous_track),
                songTitleResolver?.invoke(event.newSongId)
                    ?: context.getString(R.string.sleep_timer_label_current_track)
            )
            SleepTimerSession.Event.DurationExpired,
            is SleepTimerSession.Event.CountedPlayFinished -> null
        }
        message?.let { toastEmitter?.invoke(it) }
    }

    private fun sendToService(action: String, args: Bundle = Bundle.EMPTY): Boolean {
        val controller = mediaControllerProvider?.invoke() ?: run {
            Timber.w("Sleep timer command %s dropped: no MediaController", action)
            return false
        }
        controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        return true
    }

    private fun toast(message: String) {
        val scope = this.scope ?: return
        scope.launch { toastEmitter?.invoke(message) }
    }

    /**
     * Set a duration-based sleep timer. Replaces an end-of-track timer.
     */
    fun setSleepTimer(durationMinutes: Int) {
        val sent = sendToService(
            MusicNotificationProvider.CUSTOM_COMMAND_SET_SLEEP_TIMER_DURATION,
            Bundle().apply { putInt(MusicNotificationProvider.EXTRA_SLEEP_TIMER_MINUTES, durationMinutes) }
        )
        if (sent) {
            toast(context.getString(R.string.sleep_timer_set_for_minutes_toast, durationMinutes))
        }
    }

    /**
     * Start counted play mode - play the current song N times, then pause.
     */
    fun playCounted(count: Int) {
        sendToService(
            MusicNotificationProvider.CUSTOM_COMMAND_COUNTED_PLAY,
            Bundle().apply { putInt("count", count) }
        )
    }

    /**
     * Cancel counted play mode.
     */
    fun cancelCountedPlay() {
        sendToService(MusicNotificationProvider.CUSTOM_COMMAND_CANCEL_COUNTED_PLAY)
    }

    /**
     * Set or cancel end-of-track timer.
     */
    fun setEndOfTrackTimer(enable: Boolean, currentSongId: String?) {
        if (!enable) {
            // Cancel whenever the flag is active, so the toggle can never get stuck ON.
            if (_isEndOfTrackTimerActive.value) cancelSleepTimer()
            return
        }
        if (currentSongId == null) {
            toast(context.getString(R.string.sleep_timer_eot_no_song_toast))
            return
        }
        val sent = sendToService(
            MusicNotificationProvider.CUSTOM_COMMAND_SET_SLEEP_TIMER_END_OF_TRACK,
            Bundle().apply { putBoolean(MusicNotificationProvider.EXTRA_END_OF_TRACK_ENABLED, true) }
        )
        if (sent) {
            toast(context.getString(R.string.sleep_timer_eot_stop_at_end_toast))
        }
    }

    /**
     * Cancel any active sleep timer or EOT timer.
     */
    fun cancelSleepTimer(overrideToastMessage: String? = null, suppressDefaultToast: Boolean = false) {
        val wasAnythingActive = session.timer.value != null
        sendToService(MusicNotificationProvider.CUSTOM_COMMAND_CANCEL_SLEEP_TIMER)

        when {
            overrideToastMessage != null -> toast(overrideToastMessage)
            !suppressDefaultToast && wasAnythingActive ->
                toast(context.getString(R.string.sleep_timer_cancelled_toast))
        }
    }

    /**
     * Cleanup when ViewModel is cleared. A stale PlayerViewModel clearing leaves the newer
     * binding alone; the current one clearing re-binds the previous live PlayerViewModel and
     * restarts the session mirror in its scope. Nulling the callbacks while a PlayerViewModel
     * is still alive would make the timer display, toasts and end-of-track toggle silently
     * no-op until process restart.
     */
    fun onCleared(owner: CoroutineScope) {
        owners.remove(owner) { next ->
            if (next != null) {
                bind(next)
            } else {
                sessionMirrorJob?.cancel()
                sessionMirrorJob = null
                scope = null
                toastEmitter = null
                mediaControllerProvider = null
                songTitleResolver = null
            }
        }
    }
}
