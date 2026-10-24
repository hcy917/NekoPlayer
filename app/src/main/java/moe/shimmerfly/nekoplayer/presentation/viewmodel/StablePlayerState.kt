package moe.shimmerfly.nekoplayer.presentation.viewmodel

import androidx.compose.runtime.Immutable
import androidx.media3.common.Player
import moe.shimmerfly.nekoplayer.data.model.Song
import moe.shimmerfly.nekoplayer.data.model.Lyrics

@Immutable
data class StablePlayerState(
    val currentSong: Song? = null,
    val currentMediaItemIndex: Int = -1,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val totalDuration: Long = 0L,
    val isShuffleEnabled: Boolean = false,
    val isShuffleTransitionInProgress: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val isLoadingLyrics: Boolean = false,
    val lyrics: Lyrics? = null,
    val isBuffering: Boolean = false
)
