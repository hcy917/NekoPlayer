package moe.shimmerfly.nekoplayer.presentation.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.shimmerfly.nekoplayer.R
import moe.shimmerfly.nekoplayer.presentation.viewmodel.PlayerViewModel
import moe.shimmerfly.nekoplayer.presentation.viewmodel.SmartMixTarget
import moe.shimmerfly.nekoplayer.presentation.viewmodel.SmartMixViewModel

/**
 * The single place the Smart Mix composer is rendered, whichever screen opened it, plus the
 * "Daily Mix updated" snackbar that offers to undo "Use for today". [modifier] positions the
 * snackbar.
 */
@Composable
fun SmartMixHost(
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    smartMixViewModel: SmartMixViewModel = hiltViewModel(),
) {
    val target by smartMixViewModel.target.collectAsStateWithLifecycle()
    val preview by smartMixViewModel.mixPreview.collectAsStateWithLifecycle()
    val isGenerating by smartMixViewModel.isGenerating.collectAsStateWithLifecycle()
    val isSaving by smartMixViewModel.mixActionInProgress.collectAsStateWithLifecycle()
    val error by smartMixViewModel.error.collectAsStateWithLifecycle()
    val suggestions by smartMixViewModel.mixSuggestions.collectAsStateWithLifecycle()
    val dailyMixUndoId by smartMixViewModel.dailyMixUndoId.collectAsStateWithLifecycle()

    LaunchedEffect(smartMixViewModel, playerViewModel) {
        smartMixViewModel.messages.collect { playerViewModel.sendToast(it) }
    }

    val snackbarState = remember { SnackbarHostState() }
    val dailyMixUpdatedMessage = stringResource(R.string.mix_daily_updated)
    val undoLabel = stringResource(R.string.mix_undo)
    LaunchedEffect(dailyMixUndoId) {
        val undoId = dailyMixUndoId ?: return@LaunchedEffect
        val result = snackbarState.showSnackbar(
            message = dailyMixUpdatedMessage,
            actionLabel = undoLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            smartMixViewModel.undoDailyMixUpdate(undoId)
        } else {
            smartMixViewModel.clearDailyMixUndo(undoId)
        }
    }

    target?.let { currentTarget ->
        MixComposerSheet(
            isDailyMix = currentTarget == SmartMixTarget.DailyMix,
            preview = preview,
            isGenerating = isGenerating,
            isSaving = isSaving,
            error = error,
            onDismiss = smartMixViewModel::dismiss,
            onGenerate = smartMixViewModel::generate,
            onEditRequest = smartMixViewModel::editMixRequest,
            onClearError = smartMixViewModel::clearError,
            onPlay = {
                smartMixViewModel.takeMixPreviewForPlayback()?.let { mix ->
                    playerViewModel.playSongs(mix.songs, mix.songs.first(), mix.savedName ?: mix.suggestedName)
                }
            },
            onSave = smartMixViewModel::saveMixPreview,
            onUseForToday = smartMixViewModel::applyMixPreviewForToday,
            suggestions = suggestions,
        )
    }

    SnackbarHost(hostState = snackbarState, modifier = modifier)
}
