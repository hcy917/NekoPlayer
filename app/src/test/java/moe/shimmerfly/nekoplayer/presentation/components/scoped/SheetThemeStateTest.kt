package moe.shimmerfly.nekoplayer.presentation.components.scoped

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SheetThemeStateTest {

    private val systemScheme = lightColorScheme(primary = Color(0xFF336699))
    private val activeAlbumScheme = lightColorScheme(primary = Color(0xFFAA2244))
    private val previousAlbumScheme = lightColorScheme(primary = Color(0xFF228855))

    @Test
    fun amoled_keepsAlbumAccentsAndBlackSurfacesInPlayerSheet() {
        val darkAlbum = darkColorScheme(primary = Color(0xFFFFAA66))
        val resolved = resolvePlayerSheetTargetScheme(
            isAlbumArtTheme = true,
            hasAlbumArt = true,
            currentSongActiveScheme = darkAlbum,
            lastAlbumScheme = null,
            systemColorScheme = systemScheme,
            isAmoledTheme = true,
        )
        assertThat(resolved.primary).isEqualTo(darkAlbum.primary)
        assertThat(resolved.background).isEqualTo(Color.Black)
        assertThat(resolved.surface).isEqualTo(Color.Black)
    }

    @Test
    fun amoled_withoutArtwork_keepsFallbackAccentsAndBlackSurfaces() {
        val fallback = darkColorScheme(primary = Color(0xFF75CCAA))
        val resolved = resolvePlayerSheetTargetScheme(
            isAlbumArtTheme = true,
            hasAlbumArt = false,
            currentSongActiveScheme = null,
            lastAlbumScheme = previousAlbumScheme,
            systemColorScheme = fallback,
            isAmoledTheme = true,
        )
        assertThat(resolved.primary).isEqualTo(fallback.primary)
        assertThat(resolved.background).isEqualTo(Color.Black)
    }

    @Test
    fun resolvePlayerSheetTargetScheme_withoutAlbumArt_usesSystemScheme() {
        val resolved = resolvePlayerSheetTargetScheme(
            isAlbumArtTheme = true,
            hasAlbumArt = false,
            currentSongActiveScheme = null,
            lastAlbumScheme = previousAlbumScheme,
            systemColorScheme = systemScheme
        )

        assertThat(resolved).isSameInstanceAs(systemScheme)
    }

    @Test
    fun resolvePlayerSheetTargetScheme_withPendingAlbumPalette_reusesPreviousAlbumScheme() {
        val resolved = resolvePlayerSheetTargetScheme(
            isAlbumArtTheme = true,
            hasAlbumArt = true,
            currentSongActiveScheme = null,
            lastAlbumScheme = previousAlbumScheme,
            systemColorScheme = systemScheme
        )

        assertThat(resolved).isSameInstanceAs(previousAlbumScheme)
    }

    @Test
    fun resolvePlayerSheetTargetScheme_withReadyAlbumPalette_usesCurrentAlbumScheme() {
        val resolved = resolvePlayerSheetTargetScheme(
            isAlbumArtTheme = true,
            hasAlbumArt = true,
            currentSongActiveScheme = activeAlbumScheme,
            lastAlbumScheme = previousAlbumScheme,
            systemColorScheme = systemScheme
        )

        assertThat(resolved).isSameInstanceAs(activeAlbumScheme)
    }
}
