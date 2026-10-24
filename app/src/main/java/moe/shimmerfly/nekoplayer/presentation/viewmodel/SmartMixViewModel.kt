package moe.shimmerfly.nekoplayer.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Screen access to the shared [SmartMixStateHolder]: entry points open the composer, the host in
 * MainActivity renders it, and settings clears its history. Every instance sees the same state.
 */
@HiltViewModel
class SmartMixViewModel @Inject constructor(
    private val holder: SmartMixStateHolder,
) : ViewModel() {
    val target = holder.target
    val isGenerating = holder.isGenerating
    val error = holder.error
    val mixPreview = holder.mixPreview
    val mixActionInProgress = holder.mixActionInProgress
    val dailyMixUndoId = holder.dailyMixUndoId
    val mixSuggestions = holder.mixSuggestions
    val messages = holder.messages

    fun open(target: SmartMixTarget) = holder.open(target)
    fun dismiss() = holder.dismiss()
    fun editMixRequest() = holder.editMixRequest()
    fun clearError() = holder.clearError()
    fun generate(prompt: String, maxSongs: Int) = holder.generate(prompt, maxSongs)
    fun saveMixPreview(name: String) = holder.saveMixPreview(name)
    fun takeMixPreviewForPlayback(): MixPreview? = holder.takeMixPreviewForPlayback()
    fun applyMixPreviewForToday() = holder.applyMixPreviewForToday()
    fun undoDailyMixUpdate(undoId: Long) = holder.undoDailyMixUpdate(undoId)
    fun clearDailyMixUndo(undoId: Long) = holder.clearDailyMixUndo(undoId)

    fun clearHistory(onCleared: () -> Unit) {
        viewModelScope.launch {
            holder.clearHistory()
            onCleared()
        }
    }
}
