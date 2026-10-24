package moe.shimmerfly.nekoplayer.presentation.settings.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingHighlightModifierTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun searchTargetFarDownTheListScrollsItselfIntoView() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ProvideSettingsHighlight(HIGHLIGHT_KEY) { SettingsPage() }
        }
        composeRule.onNodeWithTag(TARGET_TAG).assertIsNotDisplayed()

        composeRule.mainClock.advanceTimeBy(1_000)

        composeRule.onNodeWithTag(TARGET_TAG).assertIsDisplayed()
    }

    @Test
    fun tagWithoutAProviderDoesNotScroll() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { SettingsPage() }

        composeRule.mainClock.advanceTimeBy(1_000)

        composeRule.onNodeWithTag(TARGET_TAG).assertIsNotDisplayed()
    }

    @Test
    fun consumedHighlightDoesNotScrollAgainAfterStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            ProvideSettingsHighlight(HIGHLIGHT_KEY) { SettingsPage() }
        }
        // Delays, the scroll and the whole pulse.
        composeRule.mainClock.advanceTimeBy(5_000)
        composeRule.onNodeWithTag(TARGET_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(LIST_TAG).performScrollToIndex(0)
        composeRule.onNodeWithTag(TARGET_TAG).assertIsNotDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.mainClock.advanceTimeBy(1_000)

        composeRule.onNodeWithTag(TARGET_TAG).assertIsNotDisplayed()
    }

    @Test
    fun leavingMidPulseDoesNotReplayTheHighlightWhenTheDestinationComesBack() {
        composeRule.mainClock.autoAdvance = false
        var showPage by mutableStateOf(true)
        composeRule.setContent {
            // Mirrors NavHost, which keeps a back stack entry's saved state in a holder while
            // a sub-screen covers it and disposes its composition.
            val stateHolder = rememberSaveableStateHolder()
            if (showPage) {
                stateHolder.SaveableStateProvider(PAGE_KEY) {
                    ProvideSettingsHighlight(HIGHLIGHT_KEY) { SettingsPage() }
                }
            }
        }
        // Scrolled into view and still pulsing.
        composeRule.mainClock.advanceTimeBy(1_200)
        composeRule.onNodeWithTag(TARGET_TAG).assertIsDisplayed()

        // Open a sub-screen, which cancels the pulse, then come back.
        showPage = false
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(TARGET_TAG).assertDoesNotExist()
        showPage = true
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(LIST_TAG).performScrollToIndex(0)
        composeRule.onNodeWithTag(TARGET_TAG).assertIsNotDisplayed()

        composeRule.mainClock.advanceTimeBy(1_000)

        composeRule.onNodeWithTag(TARGET_TAG).assertIsNotDisplayed()
    }

    /** Mirrors SettingsCategoryScreen: every row of the page sits in a single lazy item. */
    @Composable
    private fun SettingsPage() {
        LazyColumn(
            modifier = Modifier
                .height(400.dp)
                .fillMaxWidth()
                .testTag(LIST_TAG)
        ) {
            item {
                Column {
                    repeat(ROW_COUNT) { index ->
                        val rowModifier = if (index == TARGET_INDEX) {
                            Modifier
                                .settingHighlight(HIGHLIGHT_KEY, HIGHLIGHT_KEY)
                                .testTag(TARGET_TAG)
                        } else {
                            Modifier
                        }
                        Box(
                            modifier = rowModifier
                                .fillMaxWidth()
                                .height(64.dp)
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val HIGHLIGHT_KEY = "k"
        const val PAGE_KEY = "page"
        const val LIST_TAG = "list"
        const val TARGET_TAG = "target"
        const val ROW_COUNT = 40
        const val TARGET_INDEX = 35
    }
}
