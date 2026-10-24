package moe.shimmerfly.nekoplayer.presentation.viewmodel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlaylistTagPoolTest {

    @Test
    fun `untagged copy of a playlist song stays untagged after a rebuild`() {
        val pool = PlaylistTagPool(
            listOf("a" to "p", "x" to "p", "b" to "p", "x" to null)
        )

        val tags = listOf(pool.take("x"), pool.take("x"))

        assertEquals(listOf("p", null), tags)
    }

    @Test
    fun `tag goes to the first rebuilt copy even when an untagged copy sat ahead in the player`() {
        // A rebuild that keeps only one copy of the song keeps the tagged one.
        val pool = PlaylistTagPool(listOf("x" to null, "a" to "p", "x" to "p"))

        assertEquals("p", pool.take("x"))
    }

    @Test
    fun `preserved current entry's tag is not handed to its duplicate`() {
        val pool = PlaylistTagPool(listOf("x" to "p", "a" to "p", "x" to null))

        pool.consume("x", "p")

        assertNull(pool.take("x"))
    }

    @Test
    fun `preserved untagged current entry leaves the playlist copy its tag`() {
        val pool = PlaylistTagPool(listOf("x" to "p", "x" to null))

        pool.consume("x", null)

        assertEquals("p", pool.take("x"))
    }

    @Test
    fun `song missing from the player gets no tag`() {
        val pool = PlaylistTagPool(listOf("a" to "p"))

        assertNull(pool.take("z"))
    }

    @Test
    fun `every copy of a song queued from the playlist keeps its tag`() {
        val pool = PlaylistTagPool(listOf("x" to "p", "x" to "p"))

        val tags = listOf(pool.take("x"), pool.take("x"), pool.take("x"))

        assertEquals(listOf("p", "p", null), tags)
    }
}
