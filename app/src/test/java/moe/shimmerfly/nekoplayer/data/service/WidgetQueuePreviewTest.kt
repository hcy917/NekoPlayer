package moe.shimmerfly.nekoplayer.data.service

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WidgetQueuePreviewTest {

    @Test
    fun `preview starts at the player next index and follows playback order`() {
        val order = mapOf(
            3 to 1,
            1 to 4,
            4 to C.INDEX_UNSET,
        )

        val indices = widgetQueuePreviewIndices(
            firstIndex = 3,
            limit = 4,
            nextIndex = { order[it] ?: C.INDEX_UNSET },
        )

        assertThat(indices).containsExactly(3, 1, 4).inOrder()
    }

    @Test
    fun `preview stops before a repeat cycle duplicates a track`() {
        val indices = widgetQueuePreviewIndices(
            firstIndex = 2,
            limit = 4,
            nextIndex = { current -> if (current == 2) 5 else 2 },
        )

        assertThat(indices).containsExactly(2, 5).inOrder()
    }

    @Test
    fun `preview is empty when player has no next item`() {
        assertThat(
            widgetQueuePreviewIndices(
                firstIndex = C.INDEX_UNSET,
                limit = 4,
                nextIndex = { C.INDEX_UNSET },
            )
        ).isEmpty()
    }

    @Test
    fun `queue item uses media metadata and resolved artwork`() {
        val mediaItem = MediaItem.Builder()
            .setMediaId("42")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Without You")
                    .setArtist("Avicii")
                    .build()
            )
            .build()

        val queueItem = mediaItem.toWidgetQueueItem(
            fallbackArtworkUri = "content://artwork/42",
        )

        assertThat(queueItem?.id).isEqualTo(42L)
        assertThat(queueItem?.title).isEqualTo("Without You")
        assertThat(queueItem?.artist).isEqualTo("Avicii")
        assertThat(queueItem?.albumArtUri).isEqualTo("content://artwork/42")
    }

    @Test
    fun `queue item fills blank media metadata from repository fallback`() {
        val mediaItem = MediaItem.Builder()
            .setMediaId("7")
            .build()

        val queueItem = mediaItem.toWidgetQueueItem(
            fallbackTitle = "Fallback title",
            fallbackArtist = "Fallback artist",
        )

        assertThat(queueItem?.title).isEqualTo("Fallback title")
        assertThat(queueItem?.artist).isEqualTo("Fallback artist")
    }

    @Test
    fun `queue item uses display title when canonical title is absent`() {
        val mediaItem = MediaItem.Builder()
            .setMediaId("9")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setDisplayTitle("Display title")
                    .build()
            )
            .build()

        assertThat(mediaItem.toWidgetQueueItem()?.title).isEqualTo("Display title")
    }
}
