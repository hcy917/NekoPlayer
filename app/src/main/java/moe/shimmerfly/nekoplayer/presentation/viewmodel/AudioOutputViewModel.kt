package moe.shimmerfly.nekoplayer.presentation.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Backs the audio output picker; the routing choice itself lives in [AudioOutputStateHolder]. */
@HiltViewModel
class AudioOutputViewModel @Inject constructor(
    private val audioOutputStateHolder: AudioOutputStateHolder,
) : ViewModel() {

    val outputs: StateFlow<List<AudioOutputDevice>> = audioOutputStateHolder.outputs

    /** Call while the picker is visible, so the list follows devices connecting or leaving. */
    fun setPickerActive(active: Boolean) {
        if (active) audioOutputStateHolder.start() else audioOutputStateHolder.stop()
    }

    fun selectOutput(deviceId: Int) {
        audioOutputStateHolder.select(deviceId)
    }

    override fun onCleared() {
        audioOutputStateHolder.stop()
    }
}
