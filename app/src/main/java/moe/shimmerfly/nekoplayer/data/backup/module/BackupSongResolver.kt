package moe.shimmerfly.nekoplayer.data.backup.module

import moe.shimmerfly.nekoplayer.data.database.MusicDao
import moe.shimmerfly.nekoplayer.data.database.SongSummary
import moe.shimmerfly.nekoplayer.data.database.toSong
import moe.shimmerfly.nekoplayer.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton

/** Song metadata stored alongside backed-up song ids, for matching on another device. */
data class BackupSongMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long
)

/**
 * Song ids are MediaStore ids: they differ between devices and can even be reused for another
 * song after a rescan. Sections that store song ids (playlists, transition rules, bookmarks)
 * export [BackupSongMetadata] next to them, and resolve them against the current library on
 * restore.
 */
@Singleton
class BackupSongResolver @Inject constructor(private val musicDao: MusicDao) {

    /** Metadata for the local songs among [songIds]; songs the library can't describe are left out. */
    suspend fun metadataFor(songIds: Collection<String>): Map<String, BackupSongMetadata> {
        if (songIds.isEmpty()) return emptyMap()
        val wanted = songIds.toSet()
        return musicDao.getAllLocalSongSummaries()
            .filter { it.id.toString() in wanted }
            .associate { it.id.toString() to BackupSongMetadata(it.title, it.artistName, it.albumName, it.duration) }
    }

    /**
     * The library songs (any source) among [songIds], by id. Ids the library doesn't have are
     * left out, so this doubles as an existence check.
     */
    suspend fun songsFor(songIds: Collection<String>): Map<String, Song> {
        val longIds = songIds.mapNotNull { it.toLongOrNull() }.distinct()
        if (longIds.isEmpty()) return emptyMap()
        // Chunked to stay under SQLite's bound-variable limit on older devices.
        return longIds.chunked(SQLITE_CHUNK_SIZE)
            .flatMap { musicDao.getSongsByIdsListSimple(it) }
            .associate { it.id.toString() to it.toSong() }
    }

    /** A snapshot of the current library to resolve many ids against. */
    suspend fun library(): Library = Library(musicDao.getAllLocalSongSummaries())

    /**
     * Whether two titles, or two artists, are the same as far as matching goes: case and outer
     * whitespace are ignored.
     */
    fun sameTag(a: String, b: String): Boolean = normalize(a) == normalize(b)

    class Library internal constructor(summaries: List<SongSummary>) {
        private val byId = summaries.associateBy { it.id.toString() }
        private val byTitleArtist: Map<String, List<SongSummary>> =
            summaries.groupBy { matchKey(it.title, it.artistName) }
        private val byTitle: Map<String, List<SongSummary>> = summaries.groupBy { normalize(it.title) }

        /**
         * The ids of the songs titled [title], whatever their artist. For callers that only know
         * the display artist, which [resolve] can't match against the library's artist column.
         */
        fun songIdsTitled(title: String): List<String> =
            byTitle[normalize(title)].orEmpty().map { it.id.toString() }

        /**
         * The current id of the song a backup called [backupSongId], or null without a confident
         * match.
         *
         * 1. Direct id match, verified against metadata when there is some (same-device restore).
         * 2. A direct id that now names a different song falls through to metadata matching.
         * 3. Title + artist (case-insensitive), disambiguated by album, then by duration.
         * 4. Still ambiguous: no match, rather than risk the wrong song.
         */
        fun resolve(backupSongId: String, meta: BackupSongMetadata?): String? {
            val directMatch = byId[backupSongId]
            if (directMatch != null && (meta == null || metadataMatches(meta, directMatch))) return backupSongId
            if (meta == null) return null

            val candidates = byTitleArtist[matchKey(meta.title, meta.artist)] ?: return null
            if (candidates.size == 1) return candidates[0].id.toString()

            val albumMatch = candidates.filter { normalize(it.albumName) == normalize(meta.album) }
            if (albumMatch.size == 1) return albumMatch[0].id.toString()

            val durationMatch = albumMatch.ifEmpty { candidates }.filter {
                kotlin.math.abs(it.duration - meta.duration) <= DURATION_TOLERANCE_MS
            }
            return durationMatch.singleOrNull()?.id?.toString()
        }

        private fun metadataMatches(meta: BackupSongMetadata, song: SongSummary): Boolean =
            normalize(meta.title) == normalize(song.title) && normalize(meta.artist) == normalize(song.artistName)
    }

    private companion object {
        const val DURATION_TOLERANCE_MS = 2000L
        const val SQLITE_CHUNK_SIZE = 500
        fun normalize(text: String): String = text.trim().lowercase()
        fun matchKey(title: String, artist: String) = "${normalize(title)}|${normalize(artist)}"
    }
}
