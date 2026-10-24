package moe.shimmerfly.nekoplayer.data.service.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks

/** Stops ExoPlayer's silent clock when a track has no usable audio decoder. */
internal class UnsupportedAudioListener(
    private val player: Player,
    private val onUnsupported: (String?) -> Unit = {}
) : Player.Listener {
    private var unsupported = false
    private var warned = false
    private var mimeType: String? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        unsupported = false
        warned = false
        mimeType = null
    }

    override fun onTracksChanged(tracks: Tracks) {
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        unsupported = AudioDecoderPolicy.hasUnsupportedAudio(tracks)
        mimeType = audio.firstOrNull()?.getTrackFormat(0)?.sampleMimeType
        if (unsupported) pauseUnsupportedAudio()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady && unsupported) pauseUnsupportedAudio()
    }

    private fun pauseUnsupportedAudio() {
        player.pause()
        if (!warned) {
            warned = true
            onUnsupported(mimeType)
        }
    }
}
