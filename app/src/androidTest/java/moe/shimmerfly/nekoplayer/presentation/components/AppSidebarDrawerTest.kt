package moe.shimmerfly.nekoplayer.presentation.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.R
import moe.shimmerfly.nekoplayer.data.preferences.SidebarShortcutKeys
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSidebarDrawerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun listeningStatsEntryIsShownAndOpensStats() {
        val selected = mutableListOf<DrawerDestination>()
        setDrawer(
            enabledShortcuts = SidebarShortcutKeys.DEFAULT,
            onDestinationSelected = { selected += it }
        )

        composeRule.onNodeWithText(statsLabel())
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertThat(selected).containsExactly(DrawerDestination.Stats)
        }
    }

    @Test
    fun listeningStatsEntryIsHiddenWhenTheShortcutIsDisabled() {
        setDrawer(enabledShortcuts = SidebarShortcutKeys.DEFAULT - SidebarShortcutKeys.STATS)

        composeRule.onNodeWithText(statsLabel()).assertDoesNotExist()
    }

    @Test
    fun backClosesTheDrawerWithoutFinishingTheActivity() {
        val drawerState = setDrawer(enabledShortcuts = SidebarShortcutKeys.DEFAULT)
        composeRule.runOnIdle { assertThat(drawerState.isOpen).isTrue() }

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        composeRule.waitUntil(timeoutMillis = 5_000) { drawerState.isClosed }
        composeRule.runOnIdle {
            assertThat(drawerState.isClosed).isTrue()
            assertThat(composeRule.activity.isFinishing).isFalse()
        }
    }

    private fun setDrawer(
        enabledShortcuts: Set<String>,
        onDestinationSelected: (DrawerDestination) -> Unit = {}
    ): DrawerState {
        lateinit var drawerState: DrawerState
        composeRule.setContent {
            drawerState = rememberDrawerState(initialValue = DrawerValue.Open)
            MaterialTheme {
                AppSidebarDrawer(
                    drawerState = drawerState,
                    selectedRoute = DrawerDestination.Home.route,
                    enabledShortcuts = enabledShortcuts,
                    onShortcutsChanged = {},
                    onDestinationSelected = onDestinationSelected
                ) {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }
        }
        composeRule.waitForIdle()
        return drawerState
    }

    private fun statsLabel(): String =
        composeRule.activity.getString(R.string.presentation_batch_g_stats_title)
}
