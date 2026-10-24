package moe.shimmerfly.nekoplayer.data.repository

import moe.shimmerfly.nekoplayer.data.backup.module.BackupSongMetadata
import moe.shimmerfly.nekoplayer.data.backup.module.BackupSongResolver
import moe.shimmerfly.nekoplayer.data.database.AudioBookmarkDao
import moe.shimmerfly.nekoplayer.data.database.AudioBookmarkEntity
import moe.shimmerfly.nekoplayer.data.database.backupSongIdOfRestoredBookmark
import moe.shimmerfly.nekoplayer.data.database.restoredBookmarkSongId
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioBookmarkRepositoryImpl @Inject constructor(
    private val audioBookmarkDao: AudioBookmarkDao,
    private val songResolver: BackupSongResolver
) : AudioBookmarkRepository {
    override fun getAllBookmarksFlow(): Flow<List<AudioBookmarkEntity>> =
        audioBookmarkDao.getAllBookmarksFlow()

    override suspend fun getBookmarksForSong(songId: String): List<AudioBookmarkEntity> =
        audioBookmarkDao.getBookmarksForSong(songId)

    override suspend fun insertBookmark(bookmark: AudioBookmarkEntity) =
        audioBookmarkDao.insertBookmark(bookmark)

    override suspend fun deleteBookmark(id: Long) =
        audioBookmarkDao.deleteBookmark(id)

    override suspend fun deleteBookmarks(ids: Collection<Long>) {
        // Chunked to stay under SQLite's bound-variable limit on older devices.
        ids.distinct().chunked(500).forEach { audioBookmarkDao.deleteBookmarks(it) }
    }

    override suspend fun getBookmarkById(id: Long): AudioBookmarkEntity? =
        audioBookmarkDao.getBookmarkById(id)

    /**
     * Song ids are MediaStore ids, so a bookmark loses its song when the file is re-indexed
     * under a new id. A restored bookmark whose song wasn't found is kept under a
     * [restoredBookmarkSongId], which names no song. Points each such bookmark at its song: the
     * one holding its id (a restored bookmark's backup id) if that song has the stored title and
     * artist, or else the only library song that has them. Bookmarks without a confident match
     * are left alone.
     */
    override suspend fun relinkOrphanedBookmarks() {
        val bookmarks = audioBookmarkDao.getAllBookmarksOnce()
        if (bookmarks.isEmpty()) return
        val presentIds = songResolver.songsFor(bookmarks.map { it.songId }).keys
        val orphans = bookmarks.filter { it.songId !in presentIds && it.songTitle.isNotBlank() }
        if (orphans.isEmpty()) return

        val library = songResolver.library()
        val relinked = orphans.groupBy { it.songId }.flatMap { (songId, group) ->
            val sample = group.first()
            val backupSongId = backupSongIdOfRestoredBookmark(songId) ?: songId
            val newId = matchSong(library, backupSongId, sample.songTitle, sample.artistName)
                ?: return@flatMap emptyList()
            group.map { it.copy(songId = newId) }
        }
        if (relinked.isEmpty()) return

        // The stored artwork uri belonged to the old id; take the matched song's.
        val songs = songResolver.songsFor(relinked.map { it.songId })
        audioBookmarkDao.insertAll(
            relinked.map { bookmark ->
                songs[bookmark.songId]?.let { bookmark.copy(albumArtUri = it.albumArtUriString) } ?: bookmark
            }
        )
    }

    /**
     * The id of the library song titled [songTitle] by [artistName], or null without a confident
     * match. Bookmarks store the display artist, which joins a multi-artist song's artists with
     * ", " where the library's artist column keeps the tag as written ("A feat. B"). So when the
     * title and artist match nothing, the songs with that title are checked by display artist.
     */
    private suspend fun matchSong(
        library: BackupSongResolver.Library,
        songId: String,
        songTitle: String,
        artistName: String
    ): String? {
        val meta = BackupSongMetadata(songTitle, artistName, album = "", duration = 0L)
        library.resolve(songId, meta)?.let { return it }
        val titled = library.songIdsTitled(songTitle)
        if (titled.isEmpty()) return null
        return songResolver.songsFor(titled).values
            .filter { songResolver.sameTag(it.displayArtist, artistName) }
            .singleOrNull()
            ?.id
    }
}
