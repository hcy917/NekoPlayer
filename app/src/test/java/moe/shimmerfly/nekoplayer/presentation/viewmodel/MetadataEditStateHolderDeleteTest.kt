package moe.shimmerfly.nekoplayer.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.data.model.Song
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MetadataEditStateHolderDeleteTest {

    private val holder = MetadataEditStateHolder(
        songMetadataEditor = mockk(relaxed = true),
        musicRepository = mockk(relaxed = true),
        imageCacheManager = mockk(relaxed = true),
        themeStateHolder = mockk(relaxed = true),
        context = mockk(relaxed = true),
    )

    @Test
    fun deleteSong_fileAlreadyGone_reportsSuccessSoTheSongLeavesTheLibrary(@TempDir dir: File) = runTest {
        val missing = File(dir, "gone.mp3")

        assertThat(holder.deleteSong(song(missing.absolutePath))).isTrue()
    }

    private fun song(path: String) = Song(
        id = "1",
        title = "Song",
        artist = "Artist",
        genre = null,
        albumArtUriString = null,
        artistId = 1L,
        albumId = 1L,
        contentUriString = "content://media/external/audio/media/1",
        duration = 180_000L,
        bitrate = null,
        sampleRate = null,
        album = "Album",
        path = path,
        mimeType = "audio/mpeg",
    )
}
