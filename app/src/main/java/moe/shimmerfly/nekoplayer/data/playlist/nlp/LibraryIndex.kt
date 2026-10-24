package moe.shimmerfly.nekoplayer.data.playlist.nlp

import moe.shimmerfly.nekoplayer.data.model.Song
import kotlin.math.ln

/**
 * A song with its textual fields pre-normalized and pre-stemmed, so the scorer never has to
 * re-tokenize the library on the hot path.
 */
class IndexedSong(
    val song: Song,
    val titleStems: Set<String>,
    val artistStems: Set<String>,
    val albumStems: Set<String>,
    val genreStems: Set<String>,
    val normalizedArtist: String,
    val normalizedGenre: String,
    /**
     * A genre borrowed from the song's artist, set by [LibraryIndex.build] only when the song's
     * own tag is missing or unrecognized. Empty otherwise.
     */
    val inferredGenre: String = "",
) {
    /** True when the song's own tag is something [GenreTaxonomy] understands. */
    val hasRecognizedGenre = GenreTaxonomy.resolve(normalizedGenre) != null

    /**
     * The genre the engine reasons about for eligibility, family and energy: the song's own tag
     * when recognized, else [inferredGenre]. [normalizedGenre] and [genreStems] stay the raw
     * tag, so an exact tag match still outranks an inferred one and listening-taste statistics
     * only ever count what the user's files actually say.
     */
    val evidenceGenre = if (hasRecognizedGenre || inferredGenre.isEmpty()) normalizedGenre else inferredGenre
    val genreFamily = GenreTaxonomy.familyOf(evidenceGenre)
    val genreEnergy = LocalMetadataHeuristics.genreEnergy(evidenceGenre)
    val genreMoods = MoodProfile.entries.filterTo(HashSet()) { it.matchesGenre(evidenceGenre) }
    val normalizedTitle = NlpText.normalize(song.title).trim()
    val normalizedAlbum = NlpText.normalize(song.album).trim()
    val artistKey = metadataKey(song.primaryArtist.name)
    /** Individual credit identities keep collaborations eligible for any explicitly named artist. */
    val artistNames: Set<String> = (if (song.artists.isEmpty()) listOf(song.artist) else song.artists.map { it.name })
        .mapTo(HashSet()) { metadataKey(it) }.filterTo(HashSet()) { it.isNotEmpty() }
    val albumKey = metadataKey(song.album).takeIf { it.isNotEmpty() && artistKey.isNotEmpty() }
        ?.let { "$artistKey|$it" }.orEmpty()
    // Conservative duplicate recording identity: preserve live/remix titles and different durations.
    val recordingKey = metadataKey(song.title).takeIf { it.isNotEmpty() && artistKey.isNotEmpty() && song.duration > 0 }
        ?.let { "$artistKey|$it|${song.duration / 1000}" } ?: "id:${song.id}"

    /** The same song with a different [inferredGenre], reusing its tokenization. */
    fun withInferredGenre(genre: String) = IndexedSong(
        song, titleStems, artistStems, albumStems, genreStems, normalizedArtist, normalizedGenre, genre,
    )

    companion object {
        internal fun metadataKey(value: String): String = NlpText.normalize(value).trim()
            .takeUnless { it in setOf("unknown", "unknown artist", "unknown album", "<unknown>") }.orEmpty()
    }
}

/**
 * An inverted-frequency view of the whole library, built once and reused across requests.
 *
 * The index provides the two things the offline scorer needs to move beyond flat, static
 * weights:
 *  - [idf]: an inverse-document-frequency weight so that matching a rare term (an unusual
 *    band name) counts far more than matching a ubiquitous one ("love", "remix").
 *  - [knownGenres]: the genres present in the library, used for lexical genre scoring and custom
 *    genre slots. Curated genre requirements also resolve when no matching library tag exists.
 *
 * Building is O(N × tokens); the result is immutable and cached by [signatureOf] so that the
 * common case (library unchanged between requests) skips the build entirely — important for
 * large libraries where the client may be invoked repeatedly.
 */
class LibraryIndex private constructor(
    val signature: Long,
    val songs: List<IndexedSong>,
    val knownGenres: Set<String>,
    val knownGenreStems: Set<String>,
    private val documentFrequency: Map<String, Int>,
    private val documentCount: Int,
) {

    /** Ceiling weight used for terms absent from the library (treated as maximally rare). */
    private val maxIdf: Double = ln(1.0 + documentCount.coerceAtLeast(1))

    /**
     * Inverse document frequency for a stemmed term: `ln(1 + N / df)`. Common terms trend
     * toward 0; rare/unseen terms trend toward [maxIdf]. Never below a small floor so that a
     * matched term always contributes something.
     */
    fun idf(stem: String): Double {
        val df = documentFrequency[stem] ?: return maxIdf
        return ln(1.0 + documentCount.toDouble() / df).coerceAtLeast(MIN_IDF)
    }

    /**
     * Rarity multiplier in `(0, 1]`, i.e. [idf] normalized by [maxIdf]. Multiply a field's
     * base weight by this so a match on a rare term counts near full weight while a match on
     * a ubiquitous term counts near zero — keeping text scores comparable in magnitude to the
     * structural genre/decade bonuses regardless of library size.
     */
    fun termWeight(stem: String): Double = (idf(stem) / maxIdf).coerceIn(MIN_IDF, 1.0)

    companion object {
        private const val MIN_IDF = 0.05

        /**
         * O(N) fingerprint of all Song fields. Edits and favorite changes invalidate even when
         * timestamps do not change. Unchanged items reuse their tokenization in [build].
         */
        fun signatureOf(songs: List<Song>): Long {
            var hash = 1125899906842597L // large prime seed
            var maxModified = 0L
            for (song in songs) {
                // Include every Song field, including favorite/artist/title edits without timestamps.
                hash = 31 * hash + song.hashCode()
                if (song.dateModified > maxModified) maxModified = song.dateModified
            }
            return hash * 31 + maxModified + songs.size
        }

        /** Minimum share of an artist's recognized tags one genre needs before it is inherited. */
        private const val INHERITANCE_MIN_SHARE = 0.5

        /** The primary-artist keys of [songs], as [ArtistGenreTable] is keyed. */
        fun artistKeysOf(songs: List<Song>): Set<String> =
            songs.mapTo(HashSet()) { IndexedSong.metadataKey(it.primaryArtist.name) }.apply { remove("") }

        /**
         * The genre each artist's *tagged* songs agree on, keyed by artist. An artist whose tags
         * are split with no genre reaching [INHERITANCE_MIN_SHARE] contributes nothing: guessing
         * between a band's rock and ambient records would be worse than leaving a song unknown.
         */
        private fun inheritedGenres(songs: List<IndexedSong>): Map<String, String> {
            val result = HashMap<String, String>()
            val tagged = songs.filter { it.hasRecognizedGenre && it.artistKey.isNotEmpty() }
            for ((artist, artistSongs) in tagged.groupBy { it.artistKey }) {
                val dominant = artistSongs.groupBy { GenreTaxonomy.resolve(it.normalizedGenre) }
                    .values.maxByOrNull { it.size } ?: continue
                if (dominant.size < artistSongs.size * INHERITANCE_MIN_SHARE) continue
                // Keep the user's own spelling of it, most common first, ties broken stably.
                result[artist] = dominant.groupingBy { it.normalizedGenre }.eachCount()
                    .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .first().key
            }
            return result
        }

        /**
         * Builds the immutable index. Callers should cache the result keyed on [signatureOf].
         *
         * Songs with no usable genre tag borrow one from their artist: first from the tags on the
         * same artist's other songs in this library, then from [artistGenres].
         */
        fun build(
            songs: List<Song>,
            previous: LibraryIndex? = null,
            artistGenres: ArtistGenreTable = ArtistGenreTable.EMPTY,
        ): LibraryIndex {
            val signature = signatureOf(songs)
            val documentFrequency = HashMap<String, Int>()
            val knownGenres = HashSet<String>()
            val knownGenreStems = HashSet<String>()
            val indexed = ArrayList<IndexedSong>(songs.size)
            val reusable = previous?.songs?.associateBy { it.song.id }.orEmpty()

            for (song in songs.distinctBy { it.id }) {
                val cached = reusable[song.id]?.takeIf { it.song == song }
                if (cached != null) {
                    indexed.add(cached)
                    if (cached.normalizedGenre.isNotEmpty()) knownGenres.add(cached.normalizedGenre)
                    knownGenreStems.addAll(cached.genreStems)
                    val terms = cached.titleStems + cached.artistStems + cached.albumStems + cached.genreStems
                    for (term in terms) documentFrequency[term] = (documentFrequency[term] ?: 0) + 1
                    continue
                }
                val titleStems = NlpText.stemTokens(song.title).toHashSet()
                val artistStems = NlpText.stemTokens(song.displayArtist).toHashSet()
                val albumStems = NlpText.stemTokens(song.album).toHashSet()
                val normalizedGenre = song.genre?.let { NlpText.normalize(it).trim() }
                    ?.takeUnless { it in setOf("unknown", "unknown genre", "<unknown>") }.orEmpty()
                val normalizedArtist = NlpText.normalize(song.displayArtist).trim()
                val genreStems: Set<String> =
                    if (normalizedGenre.isEmpty()) emptySet() else NlpText.stemTokens(normalizedGenre).toHashSet()

                if (normalizedGenre.isNotEmpty()) {
                    knownGenres.add(normalizedGenre)
                    knownGenreStems.addAll(genreStems)
                }

                indexed.add(
                    IndexedSong(
                        song = song,
                        titleStems = titleStems,
                        artistStems = artistStems,
                        albumStems = albumStems,
                        genreStems = genreStems,
                        normalizedArtist = normalizedArtist,
                        normalizedGenre = normalizedGenre,
                    )
                )

                // A term counts once per song toward document frequency.
                val documentTerms = HashSet<String>(
                    titleStems.size + artistStems.size + albumStems.size + genreStems.size + 1
                )
                documentTerms.addAll(titleStems)
                documentTerms.addAll(artistStems)
                documentTerms.addAll(albumStems)
                documentTerms.addAll(genreStems)
                for (term in documentTerms) {
                    documentFrequency[term] = (documentFrequency[term] ?: 0) + 1
                }
            }

            // A second pass, because what a song inherits depends on its artist's other songs —
            // so a reused entry may need a different answer than it had last time.
            val inherited = inheritedGenres(indexed)
            for (i in indexed.indices) {
                val entry = indexed[i]
                val inferred = if (entry.hasRecognizedGenre || entry.artistKey.isEmpty()) "" else {
                    inherited[entry.artistKey] ?: artistGenres.genreFor(entry.artistKey).orEmpty()
                }
                if (inferred != entry.inferredGenre) indexed[i] = entry.withInferredGenre(inferred)
            }

            return LibraryIndex(
                signature = signature,
                songs = indexed,
                knownGenres = knownGenres,
                knownGenreStems = knownGenreStems,
                documentFrequency = documentFrequency,
                documentCount = songs.size,
            )
        }
    }
}
