package moe.shimmerfly.nekoplayer.data.playlist.nlp

/**
 * What genre an artist is known for, for songs whose own tag says nothing.
 *
 * A mood request is only as good as the per-song evidence behind it, and offline that evidence
 * is the genre tag — which a large share of real files do not carry. Without it a song is
 * invisible to every mood: a "running" request skipped Linkin Park tracks simply because the
 * file had no genre. This table supplies the missing fact from public knowledge instead.
 *
 * Shipped as a text asset derived from Wikidata (CC0; see THIRD_PARTY_NOTICES.md), holding
 * Wikidata's own genre *labels* rather than anything precomputed: [GenreTaxonomy] interprets
 * them here, so the asset never goes stale against the taxonomy.
 *
 * It is the weakest of three sources and is consulted last — a song's own tag, then the tags the
 * user gave the same artist's other songs (see [LibraryIndex.build]), then this. [EMPTY] knows
 * nothing, which is what callers get when the asset is missing; the engine then behaves exactly
 * as it did before the table existed.
 */
class ArtistGenreTable private constructor(private val byArtist: Map<String, String>) {

    val size: Int get() = byArtist.size

    /**
     * A normalized genre string [GenreTaxonomy] recognizes, for an artist key produced by
     * `NlpText.normalize(name).trim()`, or null when the artist is unknown.
     */
    fun genreFor(artistKey: String): String? = byArtist[artistKey]

    companion object {
        val EMPTY = ArtistGenreTable(emptyMap())

        private const val FIELD_SEPARATOR = '|'
        private const val GENRE_SEPARATOR = ';'

        /**
         * Parses the `artist-genres.txt` asset: one `artist|genre;genre` row per line, with `#`
         * comment lines. Malformed rows and artists none of whose genres the taxonomy recognizes
         * are skipped, never fatal.
         *
         * [wanted] restricts the result to those artist keys. The asset covers tens of thousands
         * of artists and a library holds a few hundred, so callers pass their own artists and
         * the rest is never retained.
         */
        fun parse(lines: Sequence<String>, wanted: Set<String>? = null): ArtistGenreTable {
            if (wanted != null && wanted.isEmpty()) return EMPTY
            val result = HashMap<String, String>()
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val split = line.indexOf(FIELD_SEPARATOR)
                if (split <= 0) continue
                val artist = line.substring(0, split)
                if (wanted != null && artist !in wanted) continue
                val genre = representative(line.substring(split + 1).split(GENRE_SEPARATOR)) ?: continue
                result[artist] = genre
            }
            return if (result.isEmpty()) EMPTY else ArtistGenreTable(result)
        }

        /**
         * Reduces an artist's unordered genre list to the one label the engine will reason with.
         * The family most of the labels agree on wins, and within it the label of median energy:
         * an act listed as "alternative rock; nu metal; pop rock; electronic rock" is a guitar
         * act of ordinary rock energy, not its single heaviest or softest label.
         *
         * The list carries no ranking, so when families tie there is no telling which one the
         * act is mainly known for. If the tied labels are at least close in energy ("country;
         * pop") the median still describes the act well enough for a mood. If they are not
         * ("ambient; art rock; electronic") the answer is null: an eclectic act is better left
         * unknown than dropped into a running mix on a coin toss.
         */
        internal fun representative(genres: List<String>): String? {
            val resolved = genres.mapNotNull { raw ->
                val genre = raw.trim()
                GenreTaxonomy.resolve(genre)?.let { genre to it }
            }
            if (resolved.isEmpty()) return null
            val families = resolved.groupBy { it.second.family }.values
            val topSize = families.maxOf { it.size }
            val leaders = families.filter { it.size == topSize }
            // Sorting by (energy, label) keeps the result independent of the input order.
            val candidates = leaders.flatten().sortedWith(compareBy({ it.second.energy }, { it.first }))
            if (leaders.size > 1 &&
                candidates.last().second.energy - candidates.first().second.energy > TIE_MAX_ENERGY_SPAN
            ) return null
            return candidates[(candidates.size - 1) / 2].first
        }

        /** Widest energy gap between tied families that still lets one label stand for the act. */
        private const val TIE_MAX_ENERGY_SPAN = 0.25
    }
}
