package moe.shimmerfly.nekoplayer.utils

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaStoreGenerationUtilsTest {

    private val snapshot = mapOf("external_primary" to 120L, "1234-5678" to 40L)

    @Test
    fun `nothing changed without a background snapshot`() {
        assertFalse(mediaStoreGenerationsChanged(before = null, after = snapshot))
    }

    @Test
    fun `identical generations mean nothing changed`() {
        assertFalse(mediaStoreGenerationsChanged(before = snapshot, after = snapshot.toMap()))
    }

    @Test
    fun `a higher generation is a change`() {
        assertTrue(
            mediaStoreGenerationsChanged(
                before = snapshot,
                after = snapshot + ("external_primary" to 121L)
            )
        )
    }

    @Test
    fun `a lower generation after a MediaStore reset is a change`() {
        assertTrue(
            mediaStoreGenerationsChanged(
                before = snapshot,
                after = snapshot + ("external_primary" to 3L)
            )
        )
    }

    @Test
    fun `an added or removed volume is a change`() {
        assertTrue(
            mediaStoreGenerationsChanged(
                before = snapshot,
                after = snapshot + ("abcd-ef01" to 1L)
            )
        )
        assertTrue(
            mediaStoreGenerationsChanged(
                before = snapshot,
                after = snapshot - "1234-5678"
            )
        )
    }

    @Test
    fun `an unreadable foreground snapshot is not a change`() {
        assertFalse(mediaStoreGenerationsChanged(before = snapshot, after = emptyMap()))
    }
}
