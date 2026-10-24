package moe.shimmerfly.nekoplayer.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_bookmarks")
data class AudioBookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "song_title") val songTitle: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "album_art_uri") val albumArtUri: String?,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    @ColumnInfo(name = "created_time") val createdTime: Long = System.currentTimeMillis()
)

/**
 * A restored bookmark whose song isn't in this library yet is kept under this prefix and the
 * backup's song id. Song ids are numbers, so it names no song, and the bookmarks screen moves the
 * bookmark to its song once the library has one with its stored title and artist.
 */
private const val RESTORED_SONG_ID_PREFIX = "restored:"

/** The song id a restored bookmark is kept under until its song is found here. */
fun restoredBookmarkSongId(backupSongId: String): String = RESTORED_SONG_ID_PREFIX + backupSongId

/** The backup's song id that [songId] keeps, or null when it isn't a [restoredBookmarkSongId]. */
fun backupSongIdOfRestoredBookmark(songId: String): String? =
    if (songId.startsWith(RESTORED_SONG_ID_PREFIX)) songId.removePrefix(RESTORED_SONG_ID_PREFIX) else null
