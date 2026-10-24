package moe.shimmerfly.nekoplayer.presentation.components

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlurEffectCacheInstrumentedTest {

    @Before
    fun requireRenderEffect() {
        // RenderEffect blur is API 31+; the lyrics only request blur on those versions.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    }

    @Test
    fun sameRadiusReusesCachedEffect() {
        val cache = BlurEffectCache()

        val first = cache.get(8f)
        val second = cache.get(8f)

        assertThat(first).isNotNull()
        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun newRadiusBuildsNewEffect() {
        val cache = BlurEffectCache()

        val eightPx = cache.get(8f)
        val ninePx = cache.get(9f)

        assertThat(ninePx).isNotNull()
        assertThat(ninePx).isNotSameInstanceAs(eightPx)
    }

    @Test
    fun zeroRadiusClearsEffectAndLaterRadiusBlursAgain() {
        val cache = BlurEffectCache()
        cache.get(9f)

        assertThat(cache.get(0f)).isNull()
        assertThat(cache.get(9f)).isNotNull()
    }
}
