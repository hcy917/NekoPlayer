package moe.shimmerfly.nekoplayer.data.playlist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlaylistOrderMergeTest {

    @Test
    fun `hidden songs keep their positions while visible songs take the new order`() {
        val merged = mergeVisiblePlaylistOrder(
            storedIds = listOf("a", "X", "b", "c", "Y"),
            newVisibleOrder = listOf("c", "a", "b"),
        )

        assertEquals(listOf("c", "X", "a", "b", "Y"), merged)
    }

    @Test
    fun `duplicate visible songs survive a reorder around a hidden song`() {
        val merged = mergeVisiblePlaylistOrder(
            storedIds = listOf("a", "x", "a", "b"),
            newVisibleOrder = listOf("a", "b", "a"),
        )

        assertEquals(listOf("a", "x", "b", "a"), merged)
    }

    @Test
    fun `without hidden songs the result is the visible order`() {
        val merged = mergeVisiblePlaylistOrder(
            storedIds = listOf("a", "b", "c"),
            newVisibleOrder = listOf("b", "c", "a"),
        )

        assertEquals(listOf("b", "c", "a"), merged)
    }

    @Test
    fun `a visible song that is not stored is rejected`() {
        val merged = mergeVisiblePlaylistOrder(
            storedIds = listOf("a", "b"),
            newVisibleOrder = listOf("b", "z"),
        )

        assertNull(merged)
    }

    @Test
    fun `a visible order missing one copy of a duplicate is rejected`() {
        val merged = mergeVisiblePlaylistOrder(
            storedIds = listOf("a", "b", "a"),
            newVisibleOrder = listOf("b", "a"),
        )

        assertNull(merged)
    }

    @Test
    fun `an empty playlist stays empty`() {
        assertEquals(emptyList<String>(), mergeVisiblePlaylistOrder(emptyList(), emptyList()))
    }
}
