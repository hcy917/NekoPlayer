package moe.shimmerfly.nekoplayer.data.repository

import androidx.paging.PagingData
import moe.shimmerfly.nekoplayer.data.model.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

interface SongRepository {
    fun getSongs(): Flow<List<Song>>
    fun getSongsByAlbum(albumId: Long): Flow<List<Song>>
    fun getSongsByArtist(artistId: Long): Flow<List<Song>>
    suspend fun searchSongs(query: String): List<Song>
    fun getSongById(songId: Long): Flow<Song?>
    fun getPaginatedSongs(sortOption: moe.shimmerfly.nekoplayer.data.model.SortOption, storageFilter: moe.shimmerfly.nekoplayer.data.model.StorageFilter): Flow<PagingData<Song>>
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getPaginatedSongs(): Flow<PagingData<Song>>
    fun getPaginatedFavoriteSongs(
        sortOption: moe.shimmerfly.nekoplayer.data.model.SortOption,
        storageFilter: moe.shimmerfly.nekoplayer.data.model.StorageFilter
    ): Flow<PagingData<Song>>
    suspend fun getFavoriteSongsOnce(
        storageFilter: moe.shimmerfly.nekoplayer.data.model.StorageFilter
    ): List<Song>
    fun getFavoriteSongCountFlow(
        storageFilter: moe.shimmerfly.nekoplayer.data.model.StorageFilter
    ): Flow<Int>
}
