package moe.shimmerfly.nekoplayer.data.repository

import moe.shimmerfly.nekoplayer.data.database.AudioBookmarkEntity
import kotlinx.coroutines.flow.Flow

interface AudioBookmarkRepository {
    fun getAllBookmarksFlow(): Flow<List<AudioBookmarkEntity>>
    suspend fun getBookmarksForSong(songId: String): List<AudioBookmarkEntity>
    suspend fun insertBookmark(bookmark: AudioBookmarkEntity)
    suspend fun deleteBookmark(id: Long)
    suspend fun deleteBookmarks(ids: Collection<Long>)
    suspend fun getBookmarkById(id: Long): AudioBookmarkEntity?
    /**
     * Re-attaches bookmarks whose song id is no longer in the library, and restored ones whose
     * song wasn't found yet, to their song's current id.
     */
    suspend fun relinkOrphanedBookmarks()
}
