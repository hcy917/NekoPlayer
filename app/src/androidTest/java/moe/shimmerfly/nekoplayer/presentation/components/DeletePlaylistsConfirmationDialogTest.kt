package moe.shimmerfly.nekoplayer.presentation.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deleting several selected playlists asks first; only Delete removes them and Cancel leaves
 * them alone.
 */
@RunWith(AndroidJUnit4::class)
class DeletePlaylistsConfirmationDialogTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var confirmCount = 0
    private var dismissCount = 0

    private fun showDialog(count: Int) {
        composeRule.setContent {
            MaterialTheme {
                DeletePlaylistsConfirmationDialog(
                    count = count,
                    onConfirm = { confirmCount++ },
                    onDismiss = { dismissCount++ }
                )
            }
        }
    }

    @Test
    fun severalPlaylistsShowThePluralTitleAndCancelOnlyDismisses() {
        showDialog(count = 3)

        composeRule.onNodeWithText(quantityString(R.plurals.delete_playlists_confirmation_title, 3))
            .assertIsDisplayed()
        composeRule.onNodeWithText(quantityString(R.plurals.delete_playlists_confirmation_body, 3))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()

        composeRule.runOnIdle {
            assertThat(dismissCount).isEqualTo(1)
            assertThat(confirmCount).isEqualTo(0)
        }
    }

    @Test
    fun deleteConfirmsExactlyOnce() {
        showDialog(count = 2)

        composeRule.onNodeWithText(string(R.string.delete_action)).performClick()

        composeRule.runOnIdle {
            assertThat(confirmCount).isEqualTo(1)
            assertThat(dismissCount).isEqualTo(0)
        }
    }

    @Test
    fun aSinglePlaylistUsesTheSingularWording() {
        showDialog(count = 1)

        composeRule.onNodeWithText(string(R.string.presentation_batch_b_delete_playlist_confirm_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.presentation_batch_b_delete_playlist_confirm_body))
            .assertIsDisplayed()
    }

    private fun string(resId: Int): String = composeRule.activity.getString(resId)

    private fun quantityString(resId: Int, count: Int): String =
        composeRule.activity.resources.getQuantityString(resId, count, count)
}
