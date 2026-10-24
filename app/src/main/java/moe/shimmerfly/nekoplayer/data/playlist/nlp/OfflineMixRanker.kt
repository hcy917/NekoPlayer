package moe.shimmerfly.nekoplayer.data.playlist.nlp

import moe.shimmerfly.nekoplayer.data.DailyMixManager.SongEngagementStats
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** All values are in prompt-score points. Taste cannot override eligibility filters. */
data class MixWeights(
    val favorite: Double = 9.0,
    val familiarity: Double = 5.0,
    val listeningQuality: Double = 8.0,
    val artistTaste: Double = 7.0,
    val genreTaste: Double = 5.0,
    val discovery: Double = 3.0,
    val playlistAffinity: Double = 12.0,
    val seedSimilarity: Double = 35.0,
    val recentlyPlayed: Double = 8.0,
    val recentlyGenerated: Double = 16.0,
    val jitter: Double = 6.0,
    val artistRepeat: Double = 12.0,
    val albumRepeat: Double = 9.0,
    val genreRepeat: Double = 2.0,
    val transition: Double = 5.0,
    val poolSize: Int = 400,
    val maxTracks: Int = 200,
    val recencyHalfLifeHours: Double = 18.0,
    val maxSeeds: Int = 32,
    val minimumSeedSimilarity: Double = 0.10,
    val familiaritySaturationPlays: Double = 20.0,
    val qualityPriorSessions: Double = 3.0,
    val similarity: ContentSimilarityWeights = ContentSimilarityWeights(),
) {
    init {
        require(poolSize in 2..5000 && maxTracks in 1..200 && recencyHalfLifeHours.isFinite() && recencyHalfLifeHours > 0)
        require(maxSeeds in 1..64 && minimumSeedSimilarity in 0.0..1.0)
        require(familiaritySaturationPlays.isFinite() && familiaritySaturationPlays > 0 &&
            qualityPriorSessions.isFinite() && qualityPriorSessions > 0)
        require(listOf(favorite, familiarity, listeningQuality, artistTaste, genreTaste, discovery,
            playlistAffinity, seedSimilarity, recentlyPlayed, recentlyGenerated, jitter,
            artistRepeat, albumRepeat, genreRepeat, transition).all { it.isFinite() && it >= 0 })
    }
}

/** Dimension contributions sum to at most 1 after clamping; missing dimensions add zero. */
data class ContentSimilarityWeights(
    val artist: Double = 0.30, val album: Double = 0.20,
    val genre: Double = 0.35, val genreFamily: Double = 0.22,
    val year: Double = 0.05, val tempo: Double = 0.15, val vibe: Double = 0.15,
    val yearScale: Double = 10.0, val bpmScale: Double = 18.0,
) {
    init {
        require(listOf(artist, album, genre, genreFamily, year, tempo, vibe).all { it.isFinite() && it >= 0 })
        require(yearScale.isFinite() && yearScale > 0 && bpmScale.isFinite() && bpmScale > 0)
    }
}

/** Source-independent IDs. Generated playlists are exposure, never positive taste evidence. */
data class MixContext(
    val seedIds: Set<String> = emptySet(),
    val playlists: List<Set<String>> = emptyList(),
    val recentMixIds: Set<String> = emptySet(),
    val nowMs: Long = System.currentTimeMillis(),
)

/** Bounded content/taste retrieval followed by greedy diversity and transition reranking.
 * O(N log P + K P), O(N + playlist memberships + P) memory; no pairwise library matrix.
 */
object OfflineMixRanker {
    /** Personalization and sequencing break ties inside fixed ten-point relevance bands. They cannot
     * overcome a stronger evidence band, even after many repetitions or recent exposures. */
    private const val RELEVANCE_BAND_WIDTH = 10.0
    private const val MAX_REPEAT_COUNT = 3
    data class Candidate(val indexed: IndexedSong, val relevance: Double)
    data class Factors(
        val relevance: Double, val taste: Double, val discovery: Double,
        val playlist: Double, val seed: Double, val recency: Double, val freshness: Double,
    ) {
        val total get() = relevance + taste + discovery + playlist + seed - recency - freshness
    }
    private data class Ranked(val indexed: IndexedSong, val score: Double, val relevanceBand: Double)

    fun rank(
        candidates: List<Candidate>, index: LibraryIndex,
        engagements: Map<String, SongEngagementStats>, count: Int, random: Random,
        bpm: Map<String, Float> = emptyMap(), vibes: Map<String, SongVibe> = emptyMap(),
        context: MixContext = MixContext(), weights: MixWeights = MixWeights(),
        explain: ((String, Factors) -> Unit)? = null,
    ): List<String> {
        if (count <= 0 || candidates.isEmpty()) return emptyList()
        val target = count.coerceAtMost(weights.maxTracks)
        val artistTaste = HashMap<String, Double>()
        val genreTaste = HashMap<String, Double>()
        val support = HashMap<String, Double>()
        for (s in index.songs) {
            val stats = engagements[s.song.id]
            val quality = quality(s, stats, weights)
            // A handful of long sessions or favorites is stronger than many short auditions.
            val positive = (if (s.song.isFavorite) 1.0 else 0.0) +
                familiarity(stats, weights) * (quality ?: 0.5)
            support[s.song.id] = positive
            if (s.artistKey.isNotEmpty()) artistTaste.merge(s.artistKey, positive, Double::plus)
            if (s.normalizedGenre.isNotEmpty()) genreTaste.merge(s.normalizedGenre, positive, Double::plus)
        }
        val maxArtist = artistTaste.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val maxGenre = genreTaste.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val seeds = index.songs.filter { it.song.id in context.seedIds }.take(weights.maxSeeds)
        val playlistScores = HashMap<String, Double>()
        for (playlist in context.playlists) {
            if (playlist.size < 2) continue
            val seedHits = playlist.count { it in context.seedIds }
            val positive = playlist.sumOf { support[it] ?: 0.0 }
            // Large catch-all playlists contribute less; no O(playlist size squared) expansion.
            for (id in playlist) {
                val evidence = if (context.seedIds.isNotEmpty()) {
                    seedHits.toDouble() / sqrt(playlist.size.toDouble())
                } else {
                    ((positive - (support[id] ?: 0.0)) / (playlist.size - 1)).coerceAtLeast(0.0)
                }
                playlistScores.merge(id, evidence, Double::plus)
            }
        }
        val compare = compareBy<Ranked> { it.relevanceBand }.thenBy { it.score }
            .thenByDescending { it.indexed.song.id }
        val pool = PriorityQueue(compare)
        val bestPerArtist = HashMap<String, Ranked>()
        val seen = HashSet<String>()
        val capacity = maxOf(target * 3, weights.poolSize)
        for ((s, relevance) in candidates) {
            if (!relevance.isFinite() || !seen.add(s.song.id) || s.song.id in context.seedIds) continue
            val stats = engagements[s.song.id]
            val quality = quality(s, stats, weights)
            val factors = Factors(
                relevance = relevance,
                taste = (if (s.song.isFavorite) weights.favorite else 0.0) +
                    weights.familiarity * familiarity(stats, weights) +
                    weights.listeningQuality * (quality?.let { (it - 0.5) * 2 } ?: 0.0) +
                    weights.artistTaste * (artistTaste[s.artistKey] ?: 0.0) / maxArtist +
                    weights.genreTaste * (genreTaste[s.normalizedGenre] ?: 0.0) / maxGenre,
                discovery = weights.discovery / sqrt(1.0 + (stats?.playCount ?: 0).coerceAtLeast(0)),
                playlist = weights.playlistAffinity * (playlistScores[s.song.id] ?: 0.0).coerceAtMost(1.0),
                seed = weights.seedSimilarity * (seeds.maxOfOrNull { similarity(s, it, bpm, vibes, weights.similarity) } ?: 0.0),
                recency = weights.recentlyPlayed * recency(stats, context.nowMs, weights),
                freshness = if (s.song.id in context.recentMixIds) weights.recentlyGenerated else 0.0,
            )
            explain?.invoke(s.song.id, factors)
            val ranked = Ranked(s, factors.total + random.nextDouble() * weights.jitter,
                kotlin.math.floor(relevance / RELEVANCE_BAND_WIDTH))
            val artist = s.artistKey.ifEmpty { "id:${s.song.id}" }
            val artistBest = bestPerArtist[artist]
            if (artistBest == null || compare.compare(ranked, artistBest) > 0) bestPerArtist[artist] = ranked
            if (pool.size < capacity) pool.add(ranked)
            else if (compare.compare(ranked, pool.peek()) > 0) { pool.poll(); pool.add(ranked) }
        }
        // Preserve alternatives before reranking: a prolific favorite artist must not fill the
        // entire shortlist and make diversity impossible regardless of the reranking penalty.
        val artistReserve = PriorityQueue(compare)
        for (ranked in bestPerArtist.values) {
            if (artistReserve.size < capacity / 2) artistReserve.add(ranked)
            else if (compare.compare(ranked, artistReserve.peek()) > 0) {
                artistReserve.poll(); artistReserve.add(ranked)
            }
        }
        val remaining = (pool + artistReserve).distinctBy { it.indexed.song.id }.toMutableList()
        val result = ArrayList<IndexedSong>(target)
        val artists = HashMap<String, Int>()
        val albums = HashMap<String, Int>()
        val genres = HashMap<String, Int>()
        val recordings = seeds.mapTo(HashSet()) { it.recordingKey }
        while (result.size < target && remaining.isNotEmpty()) {
            val previous = result.lastOrNull()
            val bestBand = remaining.asSequence().filter { it.indexed.recordingKey !in recordings }
                .maxOfOrNull { it.relevanceBand } ?: break
            // Diversity is conditional on relevance, within the already eligible candidate set.
            val alternative = previous != null && remaining.any {
                it.relevanceBand == bestBand && it.indexed.artistKey != previous.artistKey &&
                    it.indexed.recordingKey !in recordings
            }
            var best = -1
            var bestScore = Double.NEGATIVE_INFINITY
            for (i in remaining.indices) {
                val item = remaining[i]
                val s = item.indexed
                if (s.recordingKey in recordings || item.relevanceBand != bestBand) continue
                if (alternative && previous.artistKey.isNotEmpty() && s.artistKey == previous.artistKey) continue
                val score = item.score - weights.artistRepeat * (artists[s.artistKey] ?: 0).coerceAtMost(MAX_REPEAT_COUNT) -
                    weights.albumRepeat * (albums[s.albumKey] ?: 0).coerceAtMost(MAX_REPEAT_COUNT) -
                    weights.genreRepeat * (genres[s.normalizedGenre] ?: 0).coerceAtMost(MAX_REPEAT_COUNT) +
                    weights.transition * (previous?.let { similarity(s, it, bpm, vibes, weights.similarity) } ?: 0.0)
                if (score > bestScore || (score == bestScore && best >= 0 &&
                        s.song.id < remaining[best].indexed.song.id)) { best = i; bestScore = score }
            }
            if (best < 0) break
            val chosen = remaining.removeAt(best).indexed
            result.add(chosen)
            recordings.add(chosen.recordingKey)
            if (chosen.artistKey.isNotEmpty()) artists.merge(chosen.artistKey, 1, Int::plus)
            if (chosen.albumKey.isNotEmpty()) albums.merge(chosen.albumKey, 1, Int::plus)
            if (chosen.normalizedGenre.isNotEmpty()) genres.merge(chosen.normalizedGenre, 1, Int::plus)
        }
        return result.map { it.song.id }
    }

    private fun familiarity(stats: SongEngagementStats?, w: MixWeights): Double =
        (ln(1.0 + (stats?.playCount ?: 0).coerceAtLeast(0)) / ln(1.0 + w.familiaritySaturationPlays)).coerceAtMost(1.0)

    /** Average listening fraction, NOT a claimed skip/completion count. Zero-duration legacy
     * counters have unknown quality. Shrink sparse evidence toward neutral over three sessions. */
    private fun quality(s: IndexedSong, stats: SongEngagementStats?, w: MixWeights): Double? {
        if (stats == null || stats.playCount <= 0 || stats.totalPlayDurationMs <= 0 || s.song.duration <= 0) return null
        val fraction = (stats.totalPlayDurationMs.toDouble() / stats.playCount / s.song.duration).coerceIn(0.0, 1.0)
        return 0.5 + (fraction - 0.5) * stats.playCount / (stats.playCount + w.qualityPriorSessions)
    }

    private fun recency(stats: SongEngagementStats?, now: Long, w: MixWeights): Double {
        val last = stats?.lastPlayedTimestamp ?: return 0.0
        if (last <= 0 || last > now) return 0.0
        return exp(-ln(2.0) * (now - last) / (w.recencyHalfLifeHours * 3_600_000))
    }

    /** Missing dimensions contribute no fabricated similarity; metadata outweighs tempo alone. */
    internal fun similarity(a: IndexedSong, b: IndexedSong, bpm: Map<String, Float>, vibes: Map<String, SongVibe>,
        w: ContentSimilarityWeights = ContentSimilarityWeights()): Double {
        var score = 0.0
        if (a.artistKey.isNotEmpty() && a.artistKey == b.artistKey) score += w.artist
        if (a.albumKey.isNotEmpty() && a.albumKey == b.albumKey) score += w.album
        if (a.genreStems.any { it in b.genreStems }) score += w.genre
        else if (a.genreFamily != null && a.genreFamily == b.genreFamily) score += w.genreFamily
        if (a.song.year > 0 && b.song.year > 0) score += w.year * exp(-abs(a.song.year - b.song.year) / w.yearScale)
        val tempoA = bpm[a.song.id]?.takeIf { it.isFinite() && it > 0 }
        val tempoB = bpm[b.song.id]?.takeIf { it.isFinite() && it > 0 }
        if (tempoA != null && tempoB != null) score += w.tempo * exp(-abs(tempoA - tempoB) / w.bpmScale)
        val va = vibes[a.song.id]
        val vb = vibes[b.song.id]
        if (va != null && vb != null) {
            val distance = (abs(va.energy - vb.energy) + abs(va.brightness - vb.brightness) +
                abs(va.dynamics - vb.dynamics) + abs(va.percussiveness - vb.percussiveness)) / 4
            if (distance.isFinite()) score += w.vibe * (1 - distance).coerceIn(0f, 1f)
        }
        return score.coerceAtMost(1.0)
    }
}
