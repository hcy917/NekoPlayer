package moe.shimmerfly.nekoplayer.presentation.viewmodel

import moe.shimmerfly.nekoplayer.data.model.Song
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QueueStateHolderTest {

    private fun song(id: String) = Song(
        id = id,
        title = id,
        artist = "Artist",
        artistId = 1L,
        album = "Album",
        albumId = 1L,
        path = "/music/$id.mp3",
        contentUriString = "content://media/$id",
        albumArtUriString = null,
        duration = 180_000L,
        mimeType = "audio/mpeg",
        bitrate = null,
        sampleRate = null
    )

    @Test
    fun `original order keeps the playlist of each entry by position`() {
        val holder = QueueStateHolder()

        holder.setOriginalQueueOrder(listOf(song("x"), song("a"), song("x")), listOf(null, "p", "p"))

        assertEquals(listOf(null, "p", "p"), holder.originalQueuePlaylistIds)
    }

    @Test
    fun `playlist ids that do not line up with the original order are dropped`() {
        val holder = QueueStateHolder()
        holder.setOriginalQueueOrder(listOf(song("a")), listOf("p"))

        holder.setOriginalQueueOrder(listOf(song("a"), song("b")), listOf("p"))

        assertNull(holder.originalQueuePlaylistIds)
    }

    @Test
    fun `shuffled playback keeps the playlist of every original entry`() = runTest {
        val holder = QueueStateHolder()
        val songs = listOf(song("a"), song("b"), song("c"))

        holder.prepareShuffledQueueSuspending(songs, playlistId = "p")

        assertEquals(songs, holder.originalQueueOrder)
        assertEquals(listOf("p", "p", "p"), holder.originalQueuePlaylistIds)
    }
}
