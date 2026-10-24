package moe.shimmerfly.nekoplayer.presentation.components

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives a real NavHost through predictive back gestures and checks the revealed screen's dim. */
@SdkSuppress(minSdkVersion = 34)
@RunWith(AndroidJUnit4::class)
class ScreenSurfaceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    private fun showOverScreen() {
        composeRule.setContent {
            MaterialTheme {
                navController = rememberNavController()
                // Neither route is a main root tab, so the depth effects always run.
                NavHost(
                    navController = navController,
                    startDestination = UNDER,
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = {
                        slideOutHorizontally(tween(300, easing = LinearEasing)) { it }
                    }
                ) {
                    composable(UNDER) {
                        ScreenSurface(navController) {
                            Box(Modifier.fillMaxSize().background(Color.White))
                        }
                    }
                    composable(OVER) {
                        ScreenSurface(navController) {
                            Box(Modifier.fillMaxSize().background(Color.Red))
                        }
                    }
                }
            }
        }
        composeRule.runOnIdle { navController.navigate(OVER) }
        settle()
    }

    private fun settle() {
        composeRule.mainClock.autoAdvance = true
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
    }

    /** Starts a back gesture from the left edge and holds it halfway, exposing [UNDER]. */
    private fun seekHalfway() {
        val dispatcher = composeRule.activity.onBackPressedDispatcher
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, BackEventCompat.EDGE_LEFT))
        }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackProgressed(BackEventCompat(140f, 500f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.waitForIdle()
    }

    private fun cancelGesture() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        settle()
    }

    /** Red channel of a pixel near the left edge, which the sliding [OVER] screen uncovers first. */
    private fun exposedRed(): Float {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        return pixels[EXPOSED_X, pixels.height / 2].red
    }

    @Test
    fun predictiveBackDimsTheRevealedScreen() {
        showOverScreen()

        seekHalfway()

        assertThat(exposedRed()).isLessThan(0.8f)
        cancelGesture()
    }

    @Test
    fun revealedScreenIsDimmedAgainAfterACancelledGesture() {
        showOverScreen()
        seekHalfway()
        cancelGesture()
        composeRule.runOnIdle { assertThat(navController.currentDestination?.route).isEqualTo(OVER) }

        seekHalfway()

        assertThat(exposedRed()).isLessThan(0.8f)
        cancelGesture()
    }

    @Test
    fun committedBackLeavesTheIncomingScreenUndimmed() {
        showOverScreen()
        seekHalfway()

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        settle()

        composeRule.runOnIdle { assertThat(navController.currentDestination?.route).isEqualTo(UNDER) }
        assertThat(exposedRed()).isGreaterThan(0.95f)
    }

    private companion object {
        const val UNDER = "under"
        const val OVER = "over"
        const val EXPOSED_X = 8
    }
}
