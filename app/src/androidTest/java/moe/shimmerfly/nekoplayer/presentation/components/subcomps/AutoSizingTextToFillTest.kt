package moe.shimmerfly.nekoplayer.presentation.components.subcomps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The multi-selection sheet headers cap their count title at two lines and draw it bold. A long
 * translation must stay within that limit at no less than the minimum size, one that only fits
 * below the maximum size must shrink rather than end in an ellipsis, and a short one still grows
 * to fill the space.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AutoSizingTextToFillTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Composable
    private fun Title(text: String, width: Dp) {
        Box(Modifier.width(width)) {
            AutoSizingTextToFill(
                text = text,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                minFontSize = 16.sp,
                maxFontSizeLimit = 24.sp,
                maxLines = 2
            )
        }
    }

    private fun setTitle(text: String, width: Int) {
        composeRule.setContent {
            MaterialTheme {
                Title(text, width.dp)
            }
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        // The text is only composed once a size has been picked.
        composeRule.waitUntilAtLeastOneExists(hasText(text), timeoutMillis = 5_000)
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    @Test
    fun longTitleStaysWithinTwoLinesAtTheMinimumSizeOrLarger() {
        setTitle(LONG_TITLE, width = 150)

        val layout = layoutOf(LONG_TITLE)

        assertThat(layout.lineCount).isAtMost(2)
        assertThat(layout.layoutInput.style.fontSize.value).isAtLeast(16f)
    }

    @Test
    fun boldTitleTooLongForTwoLinesAtTheMaximumShrinksInsteadOfEllipsizing() {
        composeRule.setContent {
            MaterialTheme {
                // Size the box from the bold title itself so the case holds with any device font.
                // At 1.8 lines' worth at 20 sp, the title needs a third line at 24 sp and fits two
                // lines with room to spare at 16 sp. A search that measured the regular face, or
                // ignored maxLines, would settle on a size the bold two-line Text has to ellipsize.
                val style = MaterialTheme.typography.headlineSmall
                val textMeasurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val width = remember(style, textMeasurer, density) {
                    val singleLine = textMeasurer.measure(
                        text = MEDIUM_TITLE,
                        style = style.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        softWrap = false,
                        maxLines = 1
                    )
                    with(density) { (singleLine.size.width / 1.8f).toDp() }
                }
                Title(MEDIUM_TITLE, width)
            }
        }

        val layout = layoutOf(MEDIUM_TITLE)
        val fontSize = layout.layoutInput.style.fontSize.value

        assertThat(layout.lineCount).isAtMost(2)
        assertThat(layout.isLineEllipsized(layout.lineCount - 1)).isFalse()
        assertThat(layout.hasVisualOverflow).isFalse()
        assertThat(fontSize).isLessThan(24f)
        assertThat(fontSize).isAtLeast(16f)
    }

    @Test
    fun shortTitleGrowsToTheMaximumSize() {
        setTitle(SHORT_TITLE, width = 300)

        val layout = layoutOf(SHORT_TITLE)

        assertThat(layout.lineCount).isEqualTo(1)
        assertThat(layout.layoutInput.style.fontSize.value).isGreaterThan(23f)
        assertThat(layout.layoutInput.style.fontSize.value).isAtMost(24f)
    }

    private companion object {
        const val LONG_TITLE = "12 AUSGEWÄHLTE WIEDERGABELISTEN FÜR DIE GEMEINSAME WARTESCHLANGE"
        const val MEDIUM_TITLE = "24 SONGS SELECTED FROM THIS GENRE"
        const val SHORT_TITLE = "12 SONGS"
    }
}
