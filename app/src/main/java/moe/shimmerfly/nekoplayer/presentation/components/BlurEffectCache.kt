package moe.shimmerfly.nekoplayer.presentation.components

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader as AndroidShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Caches the (expensive) RenderEffect Java object so a blurred graphicsLayer doesn't
 * allocate a new native blur every animation frame. Callers should quantize the radius
 * (e.g. to whole pixels) before calling [get] so this only rebuilds when the blur visibly
 * crosses a step, instead of on every frame of a continuous animation.
 */
class BlurEffectCache {
    private var lastRadiusPx: Float = Float.NaN
    private var cached: RenderEffect? = null

    @RequiresApi(Build.VERSION_CODES.S)
    fun get(radiusPx: Float): RenderEffect? {
        if (radiusPx <= 0f) {
            lastRadiusPx = 0f
            cached = null
            return null
        }
        if (radiusPx != lastRadiusPx) {
            lastRadiusPx = radiusPx
            cached = AndroidRenderEffect
                .createBlurEffect(radiusPx, radiusPx, AndroidShader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
        return cached
    }
}
