package moe.shimmerfly.nekoplayer.data.playlist

import android.content.Context
import moe.shimmerfly.nekoplayer.data.playlist.nlp.ArtistGenreTable
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the shipped [ArtistGenreTable] asset for the artists a library actually contains.
 *
 * Unlike [ConceptTableLoader] this keeps nothing in memory: the asset is large and almost all of
 * it is irrelevant to any one library, so it is streamed and filtered each time. That only
 * happens when the library index is rebuilt, never per mix.
 *
 * Every failure path returns [ArtistGenreTable.EMPTY]; it never throws and never blocks a mix.
 */
@Singleton
class ArtistGenreLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Genres for [artistKeys] (as produced by `LibraryIndex.artistKeysOf`), where known. */
    fun load(artistKeys: Set<String>): ArtistGenreTable {
        if (artistKeys.isEmpty()) return ArtistGenreTable.EMPTY
        return try {
            context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
                ArtistGenreTable.parse(reader.lineSequence(), artistKeys)
            }.also { Timber.tag(TAG).d("Artist genres resolved: ${it.size} of ${artistKeys.size}") }
        } catch (e: Exception) {
            // Includes the asset simply not being present, which is a supported state.
            Timber.tag(TAG).w(e, "Artist genre table unavailable; untagged songs stay unclassified")
            ArtistGenreTable.EMPTY
        }
    }

    private companion object {
        const val TAG = "ArtistGenreLoader"
        const val ASSET_PATH = "nlp/artist-genres.txt"
    }
}
