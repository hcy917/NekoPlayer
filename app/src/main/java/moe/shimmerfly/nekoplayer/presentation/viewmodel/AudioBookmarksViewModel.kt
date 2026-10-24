package moe.shimmerfly.nekoplayer.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import moe.shimmerfly.nekoplayer.data.database.AudioBookmarkEntity
import moe.shimmerfly.nekoplayer.data.model.Song
import moe.shimmerfly.nekoplayer.data.repository.AudioBookmarkRepository
import moe.shimmerfly.nekoplayer.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class AudioBookmarksViewModel @Inject constructor(
    private val bookmarkRepository: AudioBookmarkRepository,
    private val musicRepository: MusicRepository
) : ViewModel() {

    val allBookmarks: StateFlow<List<AudioBookmarkEntity>> = bookmarkRepository.getAllBookmarksFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun observeSongs(songIds: List<String>): Flow<List<Song>> =
        musicRepository.getSongsByIds(songIds)

    fun addBookmark(song: Song, title: String, timestampMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val bookmark = AudioBookmarkEntity(
                songId = song.id,
                songTitle = song.title,
                artistName = song.displayArtist,
                albumArtUri = song.albumArtUriString,
                title = title,
                timestampMs = timestampMs
            )
            bookmarkRepository.insertBookmark(bookmark)
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkRepository.deleteBookmark(id)
        }
    }

    fun deleteBookmarks(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkRepository.deleteBookmarks(ids)
        }
    }

    /**
     * Re-attaches bookmarks whose song id changed (rescan, or a restore before the library was
     * scanned) to the song's current id.
     */
    fun relinkOrphanedBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { bookmarkRepository.relinkOrphanedBookmarks() }
                .onFailure { Timber.w(it, "Failed to relink orphaned bookmarks") }
        }
    }

    fun renameBookmark(id: Long, title: String) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val bookmark = bookmarkRepository.getBookmarkById(id) ?: return@launch
            bookmarkRepository.insertBookmark(bookmark.copy(title = trimmedTitle))
        }
    }
}
