package moe.shimmerfly.nekoplayer.data.playlist

import android.content.Context
import moe.shimmerfly.nekoplayer.data.playlist.nlp.ConceptTable
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the shipped [ConceptTable] asset once and keeps it for the process lifetime.
 *
 * This is the Android half of the concept table: the `nlp` package stays pure and
 * dependency-free, so asset access lives out here and the parsed table is handed in, exactly the
 * way `bpmBySongId` and `vibeBySongId` already are.
 *
 * Every failure path returns [ConceptTable.EMPTY], which is a working table that knows nothing —
 * so a missing, truncated or corrupt asset costs SmartMix its extra vocabulary and nothing else.
 * It never throws and never blocks a mix.
 */
@Singleton
class ConceptTableLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var cached: ConceptTable? = null

    /**
     * The parsed table, loaded on first use. Safe to call from any thread: a lost race costs one
     * redundant parse of a ~200KB text file, which is cheaper than holding a lock on every mix.
     */
    fun load(): ConceptTable {
        cached?.let { return it }
        val table = try {
            context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
                ConceptTable.parse(reader.lineSequence())
            }
        } catch (e: Exception) {
            // Includes the asset simply not being present, which is a supported state.
            Timber.tag(TAG).w(e, "Concept table unavailable; Smart Mix keeps its curated vocabulary")
            ConceptTable.EMPTY
        }
        Timber.tag(TAG).d("Concept table loaded: ${table.size} rows")
        cached = table
        return table
    }

    private companion object {
        const val TAG = "ConceptTableLoader"
        const val ASSET_PATH = "nlp/concepts.txt"
    }
}
