package moe.shimmerfly.nekoplayer.ui.glancewidget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.data.model.WidgetThemeColors
import org.junit.jupiter.api.Test

class WidgetThemeColorsGlanceMappingTest {

    private val palette = WidgetThemeColors(
        lightSurfaceContainer = 0xFF000001.toInt(),
        lightSurfaceContainerHigh = 0xFF000002.toInt(),
        lightTitle = 0xFF000003.toInt(),
        lightArtist = 0xFF000004.toInt(),
        lightPlayPauseBackground = 0xFF000005.toInt(),
        lightPlayPauseIcon = 0xFF000006.toInt(),
        lightPrevNextBackground = 0xFF000007.toInt(),
        lightPrevNextIcon = 0xFF000008.toInt(),
        darkSurfaceContainer = 0xFF000101.toInt(),
        darkSurfaceContainerHigh = 0xFF000102.toInt(),
        darkTitle = 0xFF000103.toInt(),
        darkArtist = 0xFF000104.toInt(),
        darkPlayPauseBackground = 0xFF000105.toInt(),
        darkPlayPauseIcon = 0xFF000106.toInt(),
        darkPrevNextBackground = 0xFF000107.toInt(),
        darkPrevNextIcon = 0xFF000108.toInt(),
    )

    @Test
    fun `play button roles follow the album play pause colors`() {
        val colors = palette.toGlanceColorProviders()
        val playBackground = role(palette.lightPlayPauseBackground, palette.darkPlayPauseBackground)
        val playIcon = role(palette.lightPlayPauseIcon, palette.darkPlayPauseIcon)

        assertThat(colors.primaryContainer).isEqualTo(playBackground)
        assertThat(colors.onPrimaryContainer).isEqualTo(playIcon)
        assertThat(colors.primary).isEqualTo(playBackground)
        assertThat(colors.onPrimary).isEqualTo(playIcon)
    }

    @Test
    fun `skip button roles follow the album previous next colors`() {
        val colors = palette.toGlanceColorProviders()

        assertThat(colors.secondaryContainer)
            .isEqualTo(role(palette.lightPrevNextBackground, palette.darkPrevNextBackground))
        assertThat(colors.onSecondaryContainer)
            .isEqualTo(role(palette.lightPrevNextIcon, palette.darkPrevNextIcon))
    }

    @Test
    fun `surface and text roles follow the album surface title and artist colors`() {
        val colors = palette.toGlanceColorProviders()
        val surface = role(palette.lightSurfaceContainer, palette.darkSurfaceContainer)

        assertThat(colors.surface).isEqualTo(surface)
        assertThat(colors.background).isEqualTo(surface)
        assertThat(colors.onSurface).isEqualTo(role(palette.lightTitle, palette.darkTitle))
        assertThat(colors.onSurfaceVariant).isEqualTo(role(palette.lightArtist, palette.darkArtist))
    }

    @Test
    fun `surface variant uses the high surface container`() {
        val colors = palette.toGlanceColorProviders()

        assertThat(colors.surfaceVariant)
            .isEqualTo(role(palette.lightSurfaceContainerHigh, palette.darkSurfaceContainerHigh))
    }

    @Test
    fun `surface variant falls back to the surface container when the high container is missing`() {
        val legacyPalette = palette.copy(
            lightSurfaceContainerHigh = 0,
            darkSurfaceContainerHigh = 0,
        )

        val colors = legacyPalette.toGlanceColorProviders()

        assertThat(colors.surfaceVariant)
            .isEqualTo(role(palette.lightSurfaceContainer, palette.darkSurfaceContainer))
    }

    private fun role(light: Int, dark: Int) = ColorProvider(day = Color(light), night = Color(dark))
}
