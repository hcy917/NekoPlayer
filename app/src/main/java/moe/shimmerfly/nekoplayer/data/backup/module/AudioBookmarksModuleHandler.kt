package moe.shimmerfly.nekoplayer.data.backup.module

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import moe.shimmerfly.nekoplayer.data.backup.model.BackupSection
import moe.shimmerfly.nekoplayer.data.database.AudioBookmarkDao
import moe.shimmerfly.nekoplayer.data.database.AudioBookmarkEntity
import moe.shimmerfly.nekoplayer.data.database.backupSongIdOfRestoredBookmark
import moe.shimmerfly.nekoplayer.data.database.restoredBookmarkSongId
import moe.shimmerfly.nekoplayer.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio bookmarks, as an array of [AudioBookmarkEntity] (the shape every version reads).
 *
 * Bookmarks name their song by MediaStore id, which differs on another device and can be
 * reused for a different song after a rescan. So each bookmark also carries its song's library
 * metadata under [SONG], and restore points it at this library's id for that song; older
 * versions ignore the extra field. A backup without it is matched on the title and artist the
 * bookmark already stores. The stored artwork uri is device-specific too, so it is refreshed
 * from the matched song. A local song that can't be matched yet, because it is missing or the
 * library hasn't been scanned, gets a [restoredBookmarkSongId] rather than an id that may name
 * another song here.
 */
@Singleton
class AudioBookmarksModuleHandler @Inject constructor(
    private val audioBookmarkDao: AudioBookmarkDao,
    private val songResolver: BackupSongResolver,
    @BackupGson private val gson: Gson
) : BackupModuleHandler {

    override val section = BackupSection.AUDIO_BOOKMARKS

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val bookmarks = audioBookmarkDao.getAllBookmarksOnce()
        val metadata = songResolver.metadataFor(bookmarks.map { it.songId })
        val array = JsonArray()
        bookmarks.forEach { bookmark ->
            val obj = gson.toJsonTree(bookmark).asJsonObject
            metadata[bookmark.songId]?.let { obj.add(SONG, gson.toJsonTree(it)) }
            array.add(obj)
        }
        gson.toJson(array)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        audioBookmarkDao.getAllBookmarksOnce().size
    }

    // Rollback restores this device's own rows, so its ids need no matching.
    override suspend fun snapshot(): String = withContext(Dispatchers.IO) {
        gson.toJson(audioBookmarkDao.getAllBookmarksOnce())
    }

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        val entries = JsonParser.parseString(payload).asJsonArray
            .filter { it.isJsonObject }
            .mapNotNull { parseEntry(it.asJsonObject) }
        val library = songResolver.library()

        var unmatched = 0
        val remapped = entries.map { (bookmark, meta) ->
            // Nothing to tell its song by, so nothing to check its id against either.
            if (meta == null) return@map bookmark
            // A backup taken before a restored bookmark was matched still has its restored id.
            val backupSongId = backupSongIdOfRestoredBookmark(bookmark.songId) ?: bookmark.songId
            val resolvedId = library.resolve(backupSongId, meta)
            when {
                resolvedId != null -> bookmark.copy(songId = resolvedId)
                // A local song's id is MediaStore's, which differs on another device: here it may
                // name another song, or one a scan hasn't reached yet will be given it. Keep the
                // bookmark under an id no song has until its own is found; the bookmarks screen
                // matches it later. The stored artwork uri is the old device's, so it goes too.
                isMediaStoreId(backupSongId) -> {
                    unmatched++
                    bookmark.copy(songId = restoredBookmarkSongId(backupSongId), albumArtUri = null)
                }
                // A cloud song's id is derived from the server's, the same on every device.
                else -> bookmark
            }
        }
        if (unmatched > 0) {
            Timber.tag(TAG).i("Bookmark restore: $unmatched kept aside until their songs are found")
        }

        val songs = songResolver.songsFor(remapped.map { it.songId })
        val withArtwork = remapped.map { bookmark ->
            val song = songs[bookmark.songId] ?: return@map bookmark
            bookmark.copy(albumArtUri = song.albumArtUriString)
        }
        audioBookmarkDao.replaceAll(withArtwork)
    }

    override suspend fun rollback(snapshot: String) = withContext(Dispatchers.IO) {
        val type = TypeToken.getParameterized(List::class.java, AudioBookmarkEntity::class.java).type
        val bookmarks: List<AudioBookmarkEntity> = gson.fromJson(snapshot, type)
        audioBookmarkDao.replaceAll(bookmarks)
    }

    /**
     * One backed-up bookmark and the metadata to match its song with (null when there is none to
     * go on), or null when it names no song. Gson ignores Kotlin nullability and defaults, so a
     * missing field arrives as null in a non-null property and would fail the insert, rolling
     * back the whole restore.
     */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS", "UNNECESSARY_SAFE_CALL")
    private fun parseEntry(obj: JsonObject): Pair<AudioBookmarkEntity, BackupSongMetadata?>? {
        val exportedMeta = obj.get(SONG)?.takeIf { it.isJsonObject }
            ?.let { runCatching { gson.fromJson(it, BackupSongMetadata::class.java) }.getOrNull() }
            ?.takeIf { it.title != null && it.artist != null }
            ?.let { it.copy(album = it.album ?: "") }
        obj.remove(SONG)
        val raw = runCatching { gson.fromJson(obj, AudioBookmarkEntity::class.java) }.getOrNull() ?: return null
        val songId = raw.songId?.trim().orEmpty().ifEmpty { return null }

        val bookmark = raw.copy(
            songId = songId,
            songTitle = raw.songTitle ?: "",
            artistName = raw.artistName ?: "",
            title = raw.title ?: "",
            timestampMs = raw.timestampMs.coerceAtLeast(0L)
        )
        val meta = exportedMeta
            ?: BackupSongMetadata(bookmark.songTitle, bookmark.artistName, album = "", duration = 0L)
                .takeIf { bookmark.songTitle.isNotBlank() }
        return bookmark to meta
    }

    private companion object {
        const val TAG = "AudioBookmarksBackup"
        const val SONG = "song"

        /**
         * Local songs are keyed by MediaStore id, a positive number. Synced cloud songs have
         * negative ids derived from the server's, and songs played straight off a server have
         * ids like "navidrome_…": both are the same on every device.
         */
        fun isMediaStoreId(songId: String): Boolean = (songId.toLongOrNull() ?: 0L) > 0L
    }
}
