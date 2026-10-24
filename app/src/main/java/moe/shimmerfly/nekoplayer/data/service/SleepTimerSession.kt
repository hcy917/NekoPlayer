package moe.shimmerfly.nekoplayer.data.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide sleep-timer state. [SleepTimerController] (inside MusicService) is the only
 * writer, because only the service can enforce a timer while the UI is gone; the UI mirrors
 * it from here.
 */
@Singleton
class SleepTimerSession @Inject constructor() {

    sealed interface Timer {
        data class Duration(val endsAtMillis: Long, val minutes: Int) : Timer
        data class EndOfTrack(val songId: String) : Timer
    }

    /** Plays the current song [total] times, then pauses. Independent of [timer]. */
    data class CountedPlay(val songId: String, val total: Int)

    sealed interface Event {
        data class EndOfTrackReached(val songId: String) : Event
        data class EndOfTrackCancelledBySongChange(val targetSongId: String, val newSongId: String?) : Event
        data object DurationExpired : Event
        data class CountedPlayFinished(val songId: String) : Event
    }

    private val _timer = MutableStateFlow<Timer?>(null)
    val timer: StateFlow<Timer?> = _timer.asStateFlow()

    private val _countedPlay = MutableStateFlow<CountedPlay?>(null)
    val countedPlay: StateFlow<CountedPlay?> = _countedPlay.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    internal fun setTimer(timer: Timer?) {
        _timer.value = timer
    }

    internal fun setCountedPlay(countedPlay: CountedPlay?) {
        _countedPlay.value = countedPlay
    }

    internal fun emit(event: Event) {
        _events.tryEmit(event)
    }
}
