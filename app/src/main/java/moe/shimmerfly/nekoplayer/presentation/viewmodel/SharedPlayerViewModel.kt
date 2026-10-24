package moe.shimmerfly.nekoplayer.presentation.viewmodel

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi

/**
 * Resolves the single, activity-scoped [PlayerViewModel].
 *
 * PlayerViewModel binds app-singleton state holders (ThemeStateHolder, LibraryStateHolder,
 * SleepTimerStateHolder, ...) to its viewModelScope and severs them in onCleared(). A bare
 * hiltViewModel() inside a NavHost destination scopes a *second* PlayerViewModel to that
 * NavBackStackEntry; when the entry is popped, its onCleared() nulls the singletons' scopes
 * while the real activity-scoped instance is still alive — silently breaking album-art
 * color extraction, library sorting and the sleep timer until the process restarts.
 *
 * Any composable that needs a PlayerViewModel without receiving one as a parameter must use
 * this instead of hiltViewModel().
 */
@OptIn(UnstableApi::class)
@Composable
fun sharedPlayerViewModel(): PlayerViewModel {
    val activity = LocalContext.current.findComponentActivity()
    return if (activity != null) hiltViewModel(viewModelStoreOwner = activity) else hiltViewModel()
}

// Dialog and ModalBottomSheet windows expose a ContextThemeWrapper around the activity.
private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
