package moe.shimmerfly.nekoplayer.presentation.components

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// Only the no-blur path runs on the JVM: the platform RenderEffect is stubbed here, so cached
// instances are covered by BlurEffectCacheInstrumentedTest.
class BlurEffectCacheTest {

    @Test
    fun get_returnsNoEffectForZeroRadius() {
        val cache = BlurEffectCache()

        assertNull(cache.get(0f))
    }

    @Test
    fun get_returnsNoEffectForNegativeRadius() {
        val cache = BlurEffectCache()

        assertNull(cache.get(-1f))
    }

    @Test
    fun get_keepsReturningNoEffectWhileRadiusStaysAtZero() {
        val cache = BlurEffectCache()

        assertNull(cache.get(0f))
        assertNull(cache.get(-1f))
        assertNull(cache.get(0f))
    }
}
