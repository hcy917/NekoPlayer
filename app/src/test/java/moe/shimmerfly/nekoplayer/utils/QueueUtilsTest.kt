package moe.shimmerfly.nekoplayer.utils

import moe.shimmerfly.nekoplayer.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

class QueueUtilsTest {

    @Test
    fun buildAnchoredShuffleQueueSuspending_handles10kSongsWithoutLosingItems() = runBlocking {
        val songs = buildSongs(10_000)
        val anchorIndex = 7_654

        val shuffled = withTimeout(5_000L) {
            QueueUtils.buildAnchoredShuffleQueueSuspending(
                currentQueue = songs,
                anchorIndex = anchorIndex,
                random = Random(42)
            )
        }

        assertEquals(songs.size, shuffled.size, "Queue size must stay the same")
        assertEquals(
            songs[anchorIndex].id,
            shuffled[anchorIndex].id,
            "Current song must stay anchored so playback is not redirected"
        )

        val originalIds = songs.map { it.id }.toSet()
        val shuffledIds = shuffled.map { it.id }.toSet()
        assertEquals(originalIds, shuffledIds, "Shuffled queue must contain the same songs")
    }

    @Test
    fun buildAnchoredShuffleQueueSuspending_yieldsForLargeQueues() = runBlocking {
        val songs = buildSongs(10_000)
        val started = CompletableDeferred<Unit>()
        var heartbeat = 0

        val heartbeatJob = launch {
            started.complete(Unit)
            while (true) {
                heartbeat++
                yield()
            }
        }

        started.await()
        yield()
        val beforeShuffleHeartbeat = heartbeat

        withTimeout(5_000L) {
            QueueUtils.buildAnchoredShuffleQueueSuspending(
                currentQueue = songs,
                anchorIndex = 4_321,
                random = Random(7)
            )
        }

        val afterShuffleHeartbeat = heartbeat
        heartbeatJob.cancelAndJoin()

        assertTrue(
            afterShuffleHeartbeat > beforeShuffleHeartbeat,
            "Shuffle should yield cooperatively so sibling coroutines can run"
        )
    }

    @Test
    fun buildAnchoredShuffleQueueSuspending_startAtZero_placesAnchorFirst() = runBlocking {
        val songs = buildSongs(32)
        val anchorIndex = 11

        val shuffled = QueueUtils.buildAnchoredShuffleQueueSuspending(
            currentQueue = songs,
            anchorIndex = anchorIndex,
            startAtZero = true,
            random = Random(99)
        )

        assertEquals(songs[anchorIndex].id, shuffled.first().id, "Anchor song must become the first item")
        assertEquals(songs.size, shuffled.size, "Queue size must stay the same")
        assertEquals(
            songs.map { it.id }.toSet(),
            shuffled.map { it.id }.toSet(),
            "Shuffled queue must contain the same songs"
        )
    }

    @Nested
    inner class NeighborArtworkUris {

        @Test
        fun `middle index returns the artwork of the previous and next songs`() {
            val queue = songsWithArtwork("art-0", "art-1", "art-2", "art-3", "art-4")

            assertEquals(listOf("art-1", "art-3"), QueueUtils.neighborArtworkUris(queue, index = 2))
        }

        @Test
        fun `first and last index only have one neighbour`() {
            val queue = songsWithArtwork("art-0", "art-1", "art-2")

            assertEquals(listOf("art-1"), QueueUtils.neighborArtworkUris(queue, index = 0))
            assertEquals(listOf("art-1"), QueueUtils.neighborArtworkUris(queue, index = 2))
        }

        @Test
        fun `single song queue has no neighbours`() {
            val queue = songsWithArtwork("art-0")

            assertEquals(emptyList<String>(), QueueUtils.neighborArtworkUris(queue, index = 0))
        }

        @Test
        fun `index outside the queue returns nothing`() {
            val queue = songsWithArtwork("art-0", "art-1", "art-2")

            assertEquals(emptyList<String>(), QueueUtils.neighborArtworkUris(queue, index = -1))
            assertEquals(emptyList<String>(), QueueUtils.neighborArtworkUris(queue, index = 3))
            assertEquals(emptyList<String>(), QueueUtils.neighborArtworkUris(emptyList(), index = 0))
        }

        @Test
        fun `neighbours without artwork are skipped`() {
            val queue = songsWithArtwork(null, "art-1", "  ", "art-3", "")

            assertEquals(emptyList<String>(), QueueUtils.neighborArtworkUris(queue, index = 1))
            assertEquals(emptyList<String>(), QueueUtils.neighborArtworkUris(queue, index = 3))
            assertEquals(listOf("art-1", "art-3"), QueueUtils.neighborArtworkUris(queue, index = 2))
        }

        @Test
        fun `neighbours from the same album are listed once`() {
            val queue = songsWithArtwork("album-a", "current", "album-a")

            assertEquals(listOf("album-a"), QueueUtils.neighborArtworkUris(queue, index = 1))
        }

        @Test
        fun `radius widens the neighbourhood`() {
            val queue = songsWithArtwork("art-0", "art-1", "art-2", "art-3", "art-4")

            assertEquals(
                listOf("art-0", "art-1", "art-3", "art-4"),
                QueueUtils.neighborArtworkUris(queue, index = 2, radius = 2)
            )
        }

        private fun songsWithArtwork(vararg artworkUris: String?): List<Song> =
            buildSongs(artworkUris.size).mapIndexed { index, song ->
                song.copy(albumArtUriString = artworkUris[index])
            }
    }

    private fun buildSongs(count: Int): List<Song> = List(count) { index ->
        Song(
            id = "song-$index",
            title = "Song $index",
            artist = "Artist",
            artistId = 1L,
            album = "Album",
            albumId = 1L,
            path = "/tmp/song-$index.mp3",
            contentUriString = "content://nekoplayer/song/$index",
            albumArtUriString = null,
            duration = 180_000L,
            mimeType = "audio/mpeg",
            bitrate = 320_000,
            sampleRate = 44_100
        )
    }
}
