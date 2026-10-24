package moe.shimmerfly.nekoplayer.ui.glancewidget

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProvider
import androidx.glance.color.ColorProviders
import androidx.glance.color.colorProviders
import androidx.glance.unit.ColorProvider
import moe.shimmerfly.nekoplayer.data.model.PlayerInfo
import moe.shimmerfly.nekoplayer.data.model.WidgetThemeColors

object AlbumArtBitmapCache {
    private const val CACHE_SIZE_BYTES = 4 * 1024 * 1024
    private val lruCache = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    fun getBitmap(key: String): Bitmap? = lruCache.get(key)

    fun putBitmap(key: String, bitmap: Bitmap) {
        if (getBitmap(key) == null) {
            lruCache.put(key, bitmap)
        }
    }

    fun getKey(byteArray: ByteArray): String {
        return byteArray.contentHashCode().toString()
    }
}

data class WidgetColors(
    val surface: ColorProvider,
    val onSurface: ColorProvider,
    val artist: ColorProvider,
    val playPauseBackground: ColorProvider,
    val playPauseIcon: ColorProvider,
    val prevNextBackground: ColorProvider,
    val prevNextIcon: ColorProvider
)

@Composable
fun PlayerInfo.getWidgetColors(): WidgetColors {
    val theme = this.themeColors
    
    return if (theme != null) {
        WidgetColors(
            surface = ColorProvider(
                day = Color(theme.lightSurfaceContainer),
                night = Color(theme.darkSurfaceContainer)
            ),
            onSurface = ColorProvider(
                day = Color(theme.lightTitle),
                night = Color(theme.darkTitle)
            ),
            artist = ColorProvider(
                day = Color(theme.lightArtist),
                night = Color(theme.darkArtist)
            ),
            playPauseBackground = ColorProvider(
                day = Color(theme.lightPlayPauseBackground),
                night = Color(theme.darkPlayPauseBackground)
            ),
            playPauseIcon = ColorProvider(
                day = Color(theme.lightPlayPauseIcon),
                night = Color(theme.darkPlayPauseIcon)
            ),
            prevNextBackground = ColorProvider(
                day = Color(theme.lightPrevNextBackground),
                night = Color(theme.darkPrevNextBackground)
            ),
            prevNextIcon = ColorProvider(
                day = Color(theme.lightPrevNextIcon),
                night = Color(theme.darkPrevNextIcon)
            )
        )
    } else {
        WidgetColors(
            surface = GlanceTheme.colors.surface,
            onSurface = GlanceTheme.colors.onSurface,
            artist = GlanceTheme.colors.onSurface,
            playPauseBackground = GlanceTheme.colors.primaryContainer,
            playPauseIcon = GlanceTheme.colors.onPrimaryContainer,
            prevNextBackground = GlanceTheme.colors.secondaryContainer,
            prevNextIcon = GlanceTheme.colors.onSecondaryContainer
        )
    }
}

/**
 * Glance theme roles for widgets that read [androidx.glance.GlanceTheme] directly.
 * The mapping mirrors [getWidgetColors] so every widget agrees on what the album
 * palette looks like: surface container, title/artist text, the primary play button
 * and the onPrimary skip buttons. Roles the album palette does not carry (error,
 * outline, inverse) borrow the closest of those.
 */
fun WidgetThemeColors.toGlanceColorProviders(): ColorProviders {
    fun role(light: Int, dark: Int) = ColorProvider(day = Color(light), night = Color(dark))

    val surface = role(lightSurfaceContainer, darkSurfaceContainer)
    val onSurface = role(lightTitle, darkTitle)
    val variant = role(
        lightSurfaceContainerHigh.takeUnless { it == 0 } ?: lightSurfaceContainer,
        darkSurfaceContainerHigh.takeUnless { it == 0 } ?: darkSurfaceContainer,
    )
    val onVariant = role(lightArtist, darkArtist)
    val playBackground = role(lightPlayPauseBackground, darkPlayPauseBackground)
    val playIcon = role(lightPlayPauseIcon, darkPlayPauseIcon)
    val skipBackground = role(lightPrevNextBackground, darkPrevNextBackground)
    val skipIcon = role(lightPrevNextIcon, darkPrevNextIcon)
    val error = ColorProvider(day = Color(0xFFBA1A1A), night = Color(0xFFFFB4AB))
    val onError = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF690005))

    return colorProviders(
        primary = playBackground,
        onPrimary = playIcon,
        primaryContainer = playBackground,
        onPrimaryContainer = playIcon,
        secondary = skipBackground,
        onSecondary = skipIcon,
        secondaryContainer = skipBackground,
        onSecondaryContainer = skipIcon,
        tertiary = playBackground,
        onTertiary = playIcon,
        tertiaryContainer = playBackground,
        onTertiaryContainer = playIcon,
        error = error,
        errorContainer = error,
        onError = onError,
        onErrorContainer = onError,
        background = surface,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = variant,
        onSurfaceVariant = onVariant,
        outline = onVariant,
        inverseOnSurface = surface,
        inverseSurface = onSurface,
        inversePrimary = playBackground,
    )
}
