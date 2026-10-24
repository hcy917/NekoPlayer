package moe.shimmerfly.nekoplayer.data.service.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class UnsupportedAudioListenerTest {
    private val player = mockk<Player>(relaxed = true)
    private val warnings = mutableListOf<String?>()
    private val listener = UnsupportedAudioListener(player) { warnings += it }

    private fun audioTracks(vararg support: Int): Tracks = Tracks(
        support.map { capability ->
            Tracks.Group(
                TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_ALAC).build()),
                false,
                intArrayOf(capability),
                booleanArrayOf(capability == C.FORMAT_HANDLED)
            )
        }
    )

    @Test
    fun unsupportedTrackPausesAndWarnsOnceEvenWhenPlayIsRetried() {
        val unsupported = audioTracks(C.FORMAT_UNSUPPORTED_TYPE)
        listener.onTracksChanged(unsupported)
        listener.onTracksChanged(unsupported)
        listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

        verify(exactly = 3) { player.pause() }
        assertThat(warnings).containsExactly(MimeTypes.AUDIO_ALAC)
    }

    @Test
    fun aSupportedAlternativeAudioTrackKeepsPlaybackRunning() {
        listener.onTracksChanged(audioTracks(C.FORMAT_UNSUPPORTED_TYPE, C.FORMAT_HANDLED))
        listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

        verify(exactly = 0) { player.pause() }
        assertThat(warnings).isEmpty()
    }

    @Test
    fun emptyTracksDuringPreparationDoNotPausePlayback() {
        listener.onTracksChanged(Tracks.EMPTY)
        verify(exactly = 0) { player.pause() }
    }

    @Test
    fun movingToAnotherItemClearsTheUnsupportedState() {
        listener.onTracksChanged(audioTracks(C.FORMAT_UNSUPPORTED_TYPE))
        listener.onMediaItemTransition(MediaItem.EMPTY, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        listener.onTracksChanged(audioTracks(C.FORMAT_HANDLED))

        verify(exactly = 1) { player.pause() }
        listener.onTracksChanged(audioTracks(C.FORMAT_UNSUPPORTED_TYPE))
        assertThat(warnings).hasSize(2)
    }
}
