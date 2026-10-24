package moe.shimmerfly.nekoplayer.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.shimmerfly.nekoplayer.R
import moe.shimmerfly.nekoplayer.presentation.components.FileExplorerContent
import moe.shimmerfly.nekoplayer.presentation.components.MiniPlayerHeight
import moe.shimmerfly.nekoplayer.presentation.viewmodel.SettingsViewModel

/**
 * Excluded directories as a destination rather than a dialog, so it matches the rest of the
 * settings pages.
 *
 * The same [FileExplorerContent] still backs the directory step of the setup wizard, where
 * it stays a dialog because it is one page of that flow rather than somewhere you navigate.
 *
 * Pending include/exclude toggles are staged in the view model and committed once when the
 * screen is left: Done and the back arrow commit before popping, while a system Back or
 * predictive-back swipe at the root pops the destination and the view model commits in
 * `onCleared`. The view model's pending flag keeps every path to a single commit.
 */
@Composable
fun ExcludedDirectoriesScreen(
    onNavigateBack: () -> Unit,
    isMiniplayerVisible: Boolean,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val currentPath by settingsViewModel.currentPath.collectAsStateWithLifecycle()
    val directoryChildren by settingsViewModel.currentDirectoryChildren.collectAsStateWithLifecycle()
    val availableStorages by settingsViewModel.availableStorages.collectAsStateWithLifecycle()
    val selectedStorageIndex by settingsViewModel.selectedStorageIndex.collectAsStateWithLifecycle()
    val isLoadingDirectories by settingsViewModel.isLoadingDirectories.collectAsStateWithLifecycle()
    val isExplorerPriming by settingsViewModel.isExplorerPriming.collectAsStateWithLifecycle()
    val isExplorerReady by settingsViewModel.isExplorerReady.collectAsStateWithLifecycle()
    val isCurrentDirectoryResolved by settingsViewModel.isCurrentDirectoryResolved.collectAsStateWithLifecycle()
    val allAudioFolders by settingsViewModel.allAudioFolders.collectAsStateWithLifecycle()
    val isLoadingAllAudioFolders by settingsViewModel.isLoadingAllAudioFolders.collectAsStateWithLifecycle()
    val explorerRoot = settingsViewModel.explorerRoot()
    val isAtRoot = settingsViewModel.isAtRoot()

    LaunchedEffect(Unit) {
        settingsViewModel.openExplorer()
    }

    val currentOnNavigateBack by rememberUpdatedState(onNavigateBack)
    val leave: () -> Unit = {
        settingsViewModel.applyPendingDirectoryRuleChanges()
        currentOnNavigateBack()
    }

    // Inside a subfolder, Back walks up the tree first, the same behaviour the dialog had.
    BackHandler(enabled = !isAtRoot) {
        settingsViewModel.navigateUp()
    }

    FileExplorerContent(
        currentPath = currentPath,
        directoryChildren = directoryChildren,
        availableStorages = availableStorages,
        selectedStorageIndex = selectedStorageIndex,
        isLoading = isLoadingDirectories,
        isPriming = isExplorerPriming,
        isReady = isExplorerReady,
        isCurrentDirectoryResolved = isCurrentDirectoryResolved,
        isAtRoot = isAtRoot,
        rootDirectory = explorerRoot,
        onNavigateTo = settingsViewModel::loadDirectory,
        onNavigateUp = settingsViewModel::navigateUp,
        onNavigateHome = { settingsViewModel.loadDirectory(explorerRoot) },
        onToggleAllowed = settingsViewModel::toggleDirectoryAllowed,
        onRefresh = settingsViewModel::refreshExplorer,
        onStorageSelected = settingsViewModel::selectStorage,
        onDone = leave,
        onDismiss = leave,
        title = stringResource(R.string.setcat_excluded_directories_title),
        navigationIcon = {
            FilledIconButton(
                modifier = Modifier.padding(start = 6.dp),
                onClick = leave,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.auth_cd_back)
                )
            }
        },
        // Leave room for the mini player only while it is actually showing.
        extraBottomPadding = if (isMiniplayerVisible) MiniPlayerHeight else 0.dp,
        allFolders = allAudioFolders,
        isLoadingAllFolders = isLoadingAllAudioFolders,
        onRequestAllFolders = settingsViewModel::loadAllAudioFolders,
        modifier = Modifier.fillMaxSize()
    )
}
