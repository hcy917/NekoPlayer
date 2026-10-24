package moe.shimmerfly.nekoplayer.presentation.components

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The New playlist dialog should open ready for typing, create the playlist from the IME Done
 * action, and treat a whitespace-only name the same way from the keyboard and the Create button.
 */
@RunWith(AndroidJUnit4::class)
class CreatePlaylistDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val createdNames = mutableListOf<String>()

    private val createLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>().getString(R.string.action_create)

    private fun showDialog() {
        composeRule.setContent {
            CreatePlaylistDialogRedesigned(
                onDismiss = {},
                onCreate = { createdNames += it }
            )
        }
    }

    private fun nameField() = composeRule.onNode(hasSetTextAction())

    private fun createButton() = composeRule.onNode(hasText(createLabel) and hasClickAction())

    @Test
    fun nameFieldIsFocusedWhenDialogOpens() {
        showDialog()

        composeRule.waitUntil(timeoutMillis = FOCUS_TIMEOUT_MS) {
            composeRule.onAllNodes(hasSetTextAction() and isFocused())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        nameField().assertIsFocused()
    }

    @Test
    fun imeDoneCreatesPlaylistWithTrimmedName() {
        showDialog()

        nameField().performTextInput("  Road trip  ")
        nameField().performImeAction()

        composeRule.runOnIdle {
            assertThat(createdNames).containsExactly("Road trip")
        }
    }

    @Test
    fun createButtonCreatesPlaylistWithTrimmedName() {
        showDialog()

        nameField().performTextInput("  Road trip  ")
        createButton().assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertThat(createdNames).containsExactly("Road trip")
        }
    }

    @Test
    fun whitespaceOnlyNameCannotCreatePlaylist() {
        showDialog()

        nameField().performTextInput("   ")
        nameField().performImeAction()

        createButton().assertIsNotEnabled()
        composeRule.runOnIdle {
            assertThat(createdNames).isEmpty()
        }
    }

    private companion object {
        const val FOCUS_TIMEOUT_MS = 5_000L
    }
}
