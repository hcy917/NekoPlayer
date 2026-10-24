package moe.shimmerfly.nekoplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import moe.shimmerfly.nekoplayer.data.preferences.ThemePreset
import moe.shimmerfly.nekoplayer.presentation.viewmodel.ColorSchemePair
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AmoledThemeTest {
    private val wallpaper = darkColorScheme(
        primary = Color(0xFF75CCAA),
        secondary = Color(0xFFAAEEBB),
        tertiary = Color(0xFFDDCC99),
        primaryContainer = Color(0xFF17382A),
        onPrimaryContainer = Color(0xFFCCEEDD),
    )
    private val nowPlaying = ColorSchemePair(
        light = lightColorScheme(primary = Color(0xFFAA5522)),
        dark = darkColorScheme(primary = Color(0xFFFFAA66), secondary = Color(0xFFEECCAA)),
    )

    private fun assertBlackCanvas(scheme: ColorScheme) {
        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.Black, scheme.surface)
        assertEquals(Color.Black, scheme.surfaceContainerLowest)
    }

    @Test
    fun `AMOLED keeps wallpaper accents on a black canvas`() {
        val result = resolveAmoledColorScheme(null) { wallpaper }
        assertBlackCanvas(result)
        assertEquals(wallpaper.primary, result.primary)
        assertEquals(wallpaper.secondary, result.secondary)
        assertEquals(wallpaper.tertiary, result.tertiary)
        assertEquals(wallpaper.primaryContainer, result.primaryContainer)
        assertEquals(wallpaper.onPrimaryContainer, result.onPrimaryContainer)
        assertEquals(wallpaper.onSurface, result.onSurface)
    }

    @Test
    fun `changing wallpaper changes AMOLED accents without losing black canvas`() {
        val updatedWallpaper = wallpaper.copy(primary = Color(0xFF88BBFF))
        val before = resolveAmoledColorScheme(null) { wallpaper }
        val after = resolveAmoledColorScheme(null) { updatedWallpaper }
        assertEquals(wallpaper.primary, before.primary)
        assertEquals(updatedWallpaper.primary, after.primary)
        assertBlackCanvas(after)
    }

    @Test
    fun `AMOLED uses the dark app-wide now-playing palette when one is active`() {
        val result = resolveAmoledColorScheme(nowPlaying) {
            error("Wallpaper should only be a fallback")
        }
        assertBlackCanvas(result)
        assertEquals(nowPlaying.dark.primary, result.primary)
        assertEquals(nowPlaying.dark.secondary, result.secondary)
        assertEquals(nowPlaying.dark.onSurface, result.onSurface)
    }

    @Test
    fun `only the AMOLED preset is AMOLED and unknown presets fall back to default`() {
        assertTrue(isAmoledThemePreset(ThemePreset.AMOLED))
        assertFalse(isAmoledThemePreset(ThemePreset.DEFAULT))
        assertEquals(ThemePreset.AMOLED, ThemePreset.sanitize("amoled"))
        assertEquals(ThemePreset.DEFAULT, ThemePreset.sanitize("amoled_album"))
        assertEquals(ThemePreset.DEFAULT, ThemePreset.sanitize("pro_gold"))
        assertEquals(ThemePreset.DEFAULT, ThemePreset.sanitize(null))
    }
}
