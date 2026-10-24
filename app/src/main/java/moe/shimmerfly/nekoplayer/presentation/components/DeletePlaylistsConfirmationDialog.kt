package moe.shimmerfly.nekoplayer.presentation.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import moe.shimmerfly.nekoplayer.R

/**
 * Asks before deleting the playlists picked in a multi-selection, which can't be undone.
 * A single playlist reuses the wording of the playlist screen's own delete dialog.
 */
@Composable
fun DeletePlaylistsConfirmationDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val title = if (count == 1) {
        stringResource(R.string.presentation_batch_b_delete_playlist_confirm_title)
    } else {
        pluralStringResource(R.plurals.delete_playlists_confirmation_title, count, count)
    }
    val body = if (count == 1) {
        stringResource(R.string.presentation_batch_b_delete_playlist_confirm_body)
    } else {
        pluralStringResource(R.plurals.delete_playlists_confirmation_body, count, count)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.delete_action),
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.cancel),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    )
}
