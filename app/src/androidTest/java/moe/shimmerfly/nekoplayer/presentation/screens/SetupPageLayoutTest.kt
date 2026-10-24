package moe.shimmerfly.nekoplayer.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.WindowInsets
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.R
import moe.shimmerfly.nekoplayer.data.preferences.AppThemeMode
import moe.shimmerfly.nekoplayer.presentation.viewmodel.SetupUiState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for setup pages on short screens and at large font scales: the primary
 * actions must stay reachable by scrolling, and the bottom bar must keep its controls above the
 * navigation bar. Screen size and font scale are overridden in-composition, so no device
 * settings are touched.
 */
@RunWith(AndroidJUnit4::class)
class SetupPageLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Composable
    private fun SmallScreenLargeFont(content: @Composable () -> Unit) {
        DeviceConfigurationOverride(
            DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 480.dp)) then
                DeviceConfigurationOverride.FontScale(2f)
        ) {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(PAGE_TAG)
                ) {
                    content()
                }
            }
        }
    }

    @Test
    fun permissionPageGrantButtonScrollsIntoViewAndClicksOnSmallScreenWithLargeFont() {
        var grantClicks = 0
        composeRule.setContent {
            SmallScreenLargeFont {
                PermissionPageLayout(
                    title = "Allow access to your music library",
                    description = "NekoPlayer needs to read the audio files on this device to " +
                        "build your library, show album art and play your songs.",
                    buttonText = GRANT_BUTTON,
                    icons = persistentListOf(
                        R.drawable.rounded_music_note_24,
                        R.drawable.rounded_album_24,
                        R.drawable.rounded_library_music_24,
                        R.drawable.rounded_artist_24,
                        R.drawable.rounded_playlist_play_24
                    ),
                    onGrantClicked = { grantClicks++ }
                ) {
                    TextButton(onClick = {}) {
                        Text(SKIP_BUTTON)
                    }
                }
            }
        }

        composeRule.onNodeWithText(GRANT_BUTTON)
            .performScrollTo()
            .assertIsDisplayed()
        assertFitsInsidePage(composeRule.onNodeWithText(GRANT_BUTTON).getBoundsInRoot().bottom.value)
        // At 2x on a 320dp screen the label no longer fits on one line: it must wrap, not be cut off.
        val grantLabelLayout = composeRule.onNodeWithText(GRANT_BUTTON, useUnmergedTree = true).textLayout()
        assertThat(grantLabelLayout.isLineEllipsized(grantLabelLayout.lineCount - 1)).isFalse()
        assertThat(grantLabelLayout.hasVisualOverflow).isFalse()
        composeRule.onNodeWithText(SKIP_BUTTON).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText(GRANT_BUTTON).performClick()
        composeRule.runOnIdle { assertThat(grantClicks).isEqualTo(1) }
    }

    @Test
    fun themePageFollowSystemCardScrollsIntoViewAndSelectsOnSmallScreenWithLargeFont() {
        var selectedMode: String? = null
        lateinit var followSystemTitle: String
        composeRule.setContent {
            followSystemTitle = stringResource(R.string.setup_theme_follow_title)
            SmallScreenLargeFont {
                ThemeSelectionPage(
                    uiState = SetupUiState(appThemeMode = AppThemeMode.DARK),
                    onModeSelected = { selectedMode = it }
                )
            }
        }

        composeRule.onNodeWithText(followSystemTitle)
            .performScrollTo()
            .assertIsDisplayed()
        assertFitsInsidePage(composeRule.onNodeWithText(followSystemTitle).getBoundsInRoot().bottom.value)

        composeRule.onNodeWithText(followSystemTitle).performClick()
        composeRule.runOnIdle { assertThat(selectedMode).isEqualTo(AppThemeMode.FOLLOW_SYSTEM) }
    }

    @Test
    fun bottomBarKeepsNextButtonAboveNavigationBarWhileItsSurfaceRunsUnderIt() {
        lateinit var nextDescription: String
        composeRule.setContent {
            nextDescription = stringResource(R.string.cd_next_step)
            // No ForcedSize here: the insets override hosts the content in its own view, which
            // uses the real density, so the inset is converted with that same density.
            val navBarHeightPx = with(LocalDensity.current) { NAV_BAR_HEIGHT.roundToPx() }
            val insets = remember(navBarHeightPx) {
                WindowInsetsCompat.Builder()
                    .setInsets(
                        WindowInsetsCompat.Type.navigationBars(),
                        Insets.of(0, 0, 0, navBarHeightPx)
                    )
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .build()
            }
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowInsets(insets)) {
                MaterialTheme {
                    val pagerState = rememberPagerState(pageCount = { 3 })
                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(PAGE_TAG),
                        bottomBar = {
                            SetupBottomBar(
                                modifier = Modifier.testTag(BOTTOM_BAR_TAG),
                                pagerState = pagerState,
                                onNextClicked = {},
                                onFinishClicked = {},
                                isNextButtonEnabled = true,
                                isFinishButtonEnabled = true
                            )
                        }
                    ) { padding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                        )
                    }
                }
            }
        }

        val pageBottom = composeRule.onNodeWithTag(PAGE_TAG).getBoundsInRoot().bottom
        val barBottom = composeRule.onNodeWithTag(BOTTOM_BAR_TAG).getBoundsInRoot().bottom
        // The merged node is the FAB itself. Its icon is centred in an 80dp container, so the
        // icon alone would clear a 48dp inset even without navigationBarsPadding.
        val nextBottom = composeRule.onNodeWithContentDescription(nextDescription)
            .getBoundsInRoot()
            .bottom

        assertThat(barBottom.value).isWithin(BOUNDS_TOLERANCE).of(pageBottom.value)
        // The bar keeps its own bottom padding on top of the inset.
        assertThat(nextBottom.value).isAtMost(
            pageBottom.value - NAV_BAR_HEIGHT.value - BAR_BOTTOM_PADDING.value + BOUNDS_TOLERANCE
        )
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun assertFitsInsidePage(nodeBottom: Float) {
        val pageBottom = composeRule.onNodeWithTag(PAGE_TAG).getBoundsInRoot().bottom.value
        assertThat(nodeBottom).isAtMost(pageBottom + BOUNDS_TOLERANCE)
    }

    private companion object {
        const val PAGE_TAG = "setup_page"
        const val BOTTOM_BAR_TAG = "setup_bottom_bar"
        const val GRANT_BUTTON = "Grant media access"
        const val SKIP_BUTTON = "Skip for now"
        const val BOUNDS_TOLERANCE = 1f
        val NAV_BAR_HEIGHT = 48.dp
        val BAR_BOTTOM_PADDING = 14.dp
    }
}
