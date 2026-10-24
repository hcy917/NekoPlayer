package moe.shimmerfly.nekoplayer.data.playlist.nlp

import moe.shimmerfly.nekoplayer.data.DailyMixManager.SongEngagementStats
import kotlin.random.Random

/**
 * A song's measured acoustic "vibe vector", decoupled from any DB/Android type so the pure
 * `nlp` package stays dependency-free. Optional: this app performs no on-device audio
 * analysis, so callers typically pass no vibes and the engine falls back to genre-derived
 * energy. Only songs with a *complete* vector (no null feature) should get an entry.
 */
data class SongVibe(
    val energy: Float,
    val brightness: Float,
    val dynamics: Float,
    val percussiveness: Float,
)

/**
 * The heart of the offline playlist curator. Turns a free-text query into a ranked list of
 * song ids using a rule-based slot filler ([parse]) plus TF‑IDF-weighted scoring ([generate]).
 *
 * Design goals:
 *  - **Graceful degradation**: measured BPM and vibe inputs are optional; without them the
 *    engine scores on genre-derived energy and text/behavioral signals alone.
 *  - **Semantic reach without an LLM**: mood/intent is matched via stemmed synonyms and
 *    multi-word phrases ([MoodProfile]), so "tunes to lift weights to" triggers the workout
 *    profile even though the words "workout"/"gym" never appear.
 *  - **Accent/inflection tolerance**: both sides go through [NlpText.normalize] + stemming,
 *    so "melancólico" == "melancolico" and "relajantes" ~ "relajante".
 *  - **Rarity-aware weighting**: text matches are scaled by [LibraryIndex.termWeight] so a
 *    rare artist name outranks a common word like "love" in a title.
 *  - **Personalized sequencing**: explicit constraints stay hard; [OfflineMixRanker] adds
 *    listening affinity, playlist relationships, discovery, freshness and diversity.
 */
object PlaylistIntentEngine {

    // Structural weights (kept from the previous implementation).
    private const val DECADE_BONUS = 25.0
    private const val GENRE_BONUS = 30.0
    private const val EXACT_ARTIST_BONUS = 40.0

    /** Reward for a song sharing a named genre's super-family without an exact stem match (a
     *  "grunge" track for a "rock" query). Smaller than [GENRE_BONUS], which rewards exact hits. */
    private const val GENRE_FAMILY_BONUS = 18.0

    // Field base weights, multiplied by the term's rarity weight in (0, 1].
    private const val ARTIST_BASE = 15.0
    private const val TITLE_BASE = 10.0
    private const val ALBUM_BASE = 5.0

    // Mood extras that are not simple genre/behavior bonuses.
    private const val CHILL_LONG_DURATION_MS = 200_000L
    private const val CHILL_DURATION_BONUS = 3.0
    private const val WORKOUT_FAMILIARITY_MIN_PLAYS = 5
    private const val WORKOUT_FAMILIARITY_BONUS = 5.0

    private const val RECENT_POOL_SIZE = 20

    // Tempo/energy scoring. Measured BPM (from embedded tags, when available) is trusted with a tight
    // gaussian; genre-derived energy is a coarse fallback with a wider curve and softer
    // weights. Both add a hard penalty when far off target so a query like "chillout @60bpm"
    // actively excludes high-energy songs instead of merely under-scoring them.
    private const val BPM_MATCH_MAX = 30.0
    private const val BPM_SIGMA = 18.0
    private const val BPM_MISMATCH_THRESHOLD = 45.0
    private const val BPM_MISMATCH_PENALTY = -20.0
    private const val GENRE_ENERGY_MATCH_MAX = 15.0
    private const val GENRE_ENERGY_SIGMA = 32.0
    private const val GENRE_ENERGY_MISMATCH_THRESHOLD = 55.0
    private const val GENRE_ENERGY_MISMATCH_PENALTY = -12.0

    /** BPM spanned by the full 0..1 genre-energy range on the UI's 60–180 slider scale. */
    private const val BPM_PER_ENERGY_UNIT = 120.0

    /**
     * [GENRE_ENERGY_SIGMA] expressed on the 0..1 genre-energy axis, so the reward curve a mood
     * applies to genre energy has exactly the same width as the one the BPM slider applies to
     * the derived pseudo-tempo. Derived rather than hand-tuned so the two cannot drift apart.
     */
    private const val GENRE_ENERGY_AXIS_SIGMA = GENRE_ENERGY_SIGMA / BPM_PER_ENERGY_UNIT

    // Measured acoustic vibe (optional caller-supplied data): proximity in the 4-feature 0..1 space to a matched
    // GENRE mood's target vector. Weighted in the same ballpark as GENRE_ENERGY_MATCH_MAX since
    // it replaces that coarse heuristic whenever a song has real vibe data. Beyond
    // VIBE_MISMATCH_THRESHOLD (Euclidean distance in the 0..1 feature space) it flips to a hard
    // penalty, mirroring bpmAffinity — otherwise the vibe path could only ever *reward*, so a
    // song whose measured vibe is the opposite of the mood would merely score ~0 instead of
    // being excluded, defeating the whole point of measuring it.
    private const val VIBE_MATCH_MAX = 15.0
    private const val VIBE_SIGMA = 0.5
    private const val VIBE_MISMATCH_THRESHOLD = 0.7
    private const val VIBE_MISMATCH_PENALTY = -25.0

    /** Penalty for songs whose genre energy sits outside a matched mood's acceptable band. */
    private const val MOOD_ENERGY_CONFLICT_PENALTY = -25.0

    /**
     * Ceiling on the summed, confidence-weighted mood bonus for a single song. Without a cap a
     * query naming several moods ("happy workout party") would let each matched profile add its
     * full [MoodProfile.bonus], swamping a more specific single-word query. Chosen to comfortably
     * exceed one strong single-mood hit (bonus 15-35 × strength up to 1.0) while still limiting
     * the combined total of several.
     */
    private const val MAX_TOTAL_MOOD_BONUS = 45.0

    /**
     * Applied to a "similar to X" seed artist's own songs, so they drop out of the results —
     * the user asked for something *like* the artist, implying not the artist itself.
     */
    private const val SIMILARITY_SEED_EXCLUSION = -50.0

    /** Reward for sharing a genre stem with the resolved "similar to X" seed artist. */
    private const val SIMILARITY_GENRE_BONUS = 30.0

    /**
     * Minimum [ConceptTable] weight before an inferred genre family is trusted. High, because
     * `queryGenreFamilies` drives a *hard* cross-family exclusion in [generate]: a wrong row
     * here does not merely misrank a mix, it empties one. Only consulted when the curated
     * aliases matched nothing at all.
     */
    private const val CONCEPT_FAMILY_MIN_WEIGHT = 0.6

    /** Minimum [ConceptTable] weight before an inferred mood is trusted. */
    private const val CONCEPT_MOOD_MIN_WEIGHT = 0.5

    /**
     * Ceiling on the trigger strength an *inferred* mood can reach, deliberately just under the
     * 0.5 a literal [MoodProfile.synonyms] hit scores. Hand-curated vocabulary is evidence of
     * what the word means; a table row is an inference about it, and must never outrank one.
     */
    private const val CONCEPT_MOOD_STRENGTH = 0.45

    /**
     * UI-injected directives appended to the free-text prompt by MixComposerSheet.
     * Parsed into structured slots and stripped so their words never leak into text matching.
     */
    private val TARGET_BPM_DIRECTIVE = "\\[\\s*target\\s+bpm\\s*:\\s*(\\d+)[^\\]]*\\]".toRegex(RegexOption.IGNORE_CASE)
    private val TARGET_LENGTH_DIRECTIVE = "\\[\\s*target\\s+length\\s*:[^\\]]*\\]".toRegex(RegexOption.IGNORE_CASE)
    private val TEXT_BPM = "\\b(\\d{2,3})\\s*bpm\\b".toRegex(RegexOption.IGNORE_CASE)
    private val SCOPE_TOKENS = "[\\p{L}\\p{Nd}#]+|[,;.!?]".toRegex()
    private val SCOPE_BOUNDARIES = setOf("but", "pero", "for", "para", "with", "con", "from", "by", "de")
    private val STRICT_CUES = setOf("only", "solo", "solamente", "exclusively", "exclusivamente")

    /** Structured interpretation of a query against a specific library. */
    data class ParsedQuery(
        val normalizedQuery: String,
        val contentStems: Set<String>,
        /** Per-term rarity weight in (0, 1], resolved from the library at parse time. */
        val termWeights: Map<String, Double>,
        val yearRange: IntRange?,
        val matchedGenreStems: Set<String>,
        val moods: Set<MoodProfile>,
        /** Explicit tempo target from the UI's BPM slider, when present in the prompt. */
        val targetBpm: Float? = null,
        /** Per-mood trigger confidence in `[0, 1]`; see [MoodProfile.matchStrength]. */
        val moodStrengths: Map<MoodProfile, Double> = emptyMap(),
        /** Stems from negated scopes, assigned to exclusions rather than positive rewards. */
        val negatedStems: Set<String> = emptySet(),
        /** Negated free text after resolved artist/genre spans have been assigned typed slots. */
        val lexicalNegatedStems: Set<String> = negatedStems,
        /** When the user asked for songs *like* an artist: that artist's normalized name. */
        val similarToArtist: String? = null,
        /** Genre stems shared across [similarToArtist]'s catalog, for the similarity bonus. */
        val similarSeedGenreStems: Set<String> = emptySet(),
        /** Genre super-families the user explicitly named ("rock", "phonk"); drives family reward
         *  and cross-family exclusion. Empty for mood/free-text-only queries. */
        val queryGenreFamilies: Set<GenreFamily> = emptySet(),
        /** Exact local artist identities, as alternatives within a required artist slot. */
        val requiredArtists: Set<String> = emptySet(),
        val excludedArtists: Set<String> = emptySet(),
        /** Concrete genre alternatives, evaluated using genre metadata alone. */
        val requiredGenres: Set<GenreTaxonomy.Entry> = emptySet(),
        val excludedGenres: Set<GenreTaxonomy.Entry> = emptySet(),
        val requiredGenreTags: Set<String> = emptySet(),
        val excludedYearRanges: List<IntRange> = emptyList(),
        val strictSelection: Boolean = false,
        val requiredTextStems: Set<String> = emptySet(),
        /** Genre-derived mood associations are preferences; explicit mood words are requirements. */
        val requiredMoods: Set<MoodProfile> = moods,
        /** Natural-language BPM is measured tempo; the UI directive is an energy control. */
        val requiresMeasuredBpm: Boolean = false,
    ) {
        val hasSelectionSignal: Boolean
            get() = contentStems.isNotEmpty() || yearRange != null

        /** True when the query expresses a tempo/energy preference, explicit or via mood. */
        val hasEnergySignal: Boolean
            get() = targetBpm != null || moods.any { it.kind == MoodKind.GENRE }

        /** The tempo the query is aiming for: explicit slider first, then mood implication. */
        val effectiveTargetBpm: Float?
            get() = targetBpm ?: moods.mapNotNull { it.preferredBpm }.let { prefs ->
                if (prefs.isEmpty()) null else prefs.sum() / prefs.size
            }
    }

    /**
     * Extracts decade, genre, mood, tempo and free-text slots from [query]. Pure and side-effect
     * free. [concepts] generalizes the curated vocabulary to words nobody listed; the default
     * empty table reproduces the hand-written behavior exactly.
     */
    fun parse(
        query: String,
        index: LibraryIndex,
        conceptTable: ConceptTable = ConceptTable.EMPTY,
    ): ParsedQuery {
        // Structured directives come out first so "target"/"bpm"/"60" never pollute the
        // free-text terms (they used to score as ordinary words and break mood detection).
        val freeTextQuery = query
            .replace(TARGET_BPM_DIRECTIVE, " ")
            .replace(TARGET_LENGTH_DIRECTIVE, " ")
        val textBpm = TEXT_BPM.find(freeTextQuery)?.groupValues?.get(1)?.toFloatOrNull()
        // A tempo explicitly written by the user takes precedence over the energy control.
        val targetBpm = (textBpm ?: TARGET_BPM_DIRECTIVE.find(query)?.groupValues?.get(1)?.toFloatOrNull())
            ?.takeIf { it in 20f..400f }
        val cleanQuery = freeTextQuery.replace(TEXT_BPM, " ")

        val normalizedQuery = NlpText.normalize(cleanQuery).trim()
        if (normalizedQuery.isEmpty()) {
            return ParsedQuery(
                normalizedQuery, emptySet(), emptyMap(), null, emptySet(), emptySet(), targetBpm,
                requiresMeasuredBpm = textBpm != null,
            )
        }

        val tokens = SCOPE_TOKENS.findAll(normalizedQuery).map { it.value }.toList()
        val artistTokens = artistTokenIndices(tokens, index)
        val strictSelection = tokens.indices.any { tokens[it] in STRICT_CUES && it !in artistTokens }
        var yearRange: IntRange? = null
        val contentStems = HashSet<String>()
        val excludedYearRanges = ArrayList<IntRange>()
        val positiveWords = ArrayList<String>()
        val negativeWords = ArrayList<String>()
        var negating = false
        val negatedStems = HashSet<String>()
        for ((position, token) in tokens.withIndex()) {
            val inArtist = position in artistTokens
            // A comma continues a list; a new clause or explicit positive scope ends it.
            if (!inArtist && ((negating && token in SCOPE_BOUNDARIES) || token in setOf(";", ".", "!", "?"))) {
                negating = false
                positiveWords.add("__boundary__")
                negativeWords.add("__boundary__")
            }
            if (!inArtist && NlpLexicon.isNegationCue(token)) {
                negating = true
                positiveWords.add("__boundary__")
                negativeWords.add("__boundary__")
                continue
            }
            if (!inArtist && token in STRICT_CUES) continue
            if (token.length == 1 && !token[0].isLetterOrDigit()) continue
            if (negating) negativeWords.add(token) else positiveWords.add(token)
            val decade = if (inArtist) null else decadeRangeOf(token)
            if (decade != null) {
                if (negating) excludedYearRanges.add(decade) else yearRange = decade
                continue
            }
            if (NlpLexicon.isStopWord(token) || token.length < 2) continue
            val stem = NlpText.stem(token)
            if (negating) {
                negatedStems.add(stem)
            } else {
                contentStems.add(stem)
            }
        }
        val positiveText = positiveWords.joinToString(" ")
        val negativeText = negativeWords.joinToString(" ")
        val seed = resolveSeedArtist(positiveText, index)
        val requiredArtists = if (seed != null) emptySet() else resolveArtists(positiveText, index)
        val excludedArtists = resolveArtists(negativeText, index)
        // Artist names such as Train, Jungle or The National are entities, not mood cues.
        val descriptorText = requiredArtists.fold(positiveText) { text, artist ->
            GenreTaxonomy.maskPhrase(text, artist)
        }
        val descriptorStems = NlpText.tokenize(descriptorText)
            .filterNot { NlpLexicon.isStopWord(it) || it == "boundary" || decadeRangeOf(it) != null }
            .mapTo(HashSet()) { NlpText.stem(it) }
        val termWeights = contentStems.associateWith { index.termWeight(it) }
        val matchedGenreStems = descriptorStems.intersect(index.knownGenreStems)
        // Ordinary music words double as act names - Train, Jungle, Alone. When the query names an
        // artist in this library the user is naming something, not describing a vibe, so the table
        // stands down completely: an inference here would hide the very song they asked for, and it
        // would do so through *either* axis (a family exclusion, or a mood's energy band). Curated
        // aliases are untouched by this - "metal" is the user naming a genre even if some band is
        // called Metal Church.
        val concepts = if (requiredArtists.isNotEmpty()) ConceptTable.EMPTY else conceptTable

        // Curated aliases first, and they win outright. The table is consulted only where they
        // left a hole: `"heavy"` named no family at all, which is how it fell through to
        // WORKOUT's pop-spanning energy reading. Union of inferred families is fine — a broader
        // set is a *weaker* exclusion, so an ambiguous word errs toward permissiveness.
        val curatedFamilies = GenreTaxonomy.queryFamilies(descriptorStems, descriptorText)
        val inferredGenreFamilies =
            if (curatedFamilies.isEmpty()) inferredFamilies(descriptorStems, concepts) else emptySet()
        val queryGenreFamilies = curatedFamilies.ifEmpty { inferredGenreFamilies }
        val moodStrengths = buildMap {
            for (mood in MoodProfile.entries) {
                val strength = mood.matchStrength(descriptorStems, descriptorText)
                if (strength > 0.0) put(mood, strength)
            }
            // Per-word gap filling: a stem any synonym list already knows is left alone, so a
            // curated hit is never weakened or double-counted by an inferred one.
            for (stem in descriptorStems) {
                if (MoodProfile.entries.any { stem in it.triggerStems }) continue
                val inferred = concepts.lookup(stem)?.moods ?: continue
                for ((mood, weight) in inferred) {
                    if (weight < CONCEPT_MOOD_MIN_WEIGHT) continue
                    // Max, not sum: several inferred words are still only an inference.
                    put(mood, maxOf(get(mood) ?: 0.0, weight * CONCEPT_MOOD_STRENGTH))
                }
            }
        }
        val moods = moodStrengths.keys
        val requiredGenres = GenreTaxonomy.queryGenres(descriptorStems, descriptorText)
        val excludedGenres = GenreTaxonomy.queryGenres(negatedStems, negativeText)
        val resolvedNegativeStems = excludedArtists.flatMap(NlpText::stemTokens).toSet() +
            excludedGenres.flatMap { it.queryAliases.flatMap(NlpText::stemTokens) }
        val moodConstraintText = requiredGenres.flatMap { it.queryAliases }.sortedByDescending { it.length }
            .fold(descriptorText) { text, genre -> GenreTaxonomy.maskPhrase(text, genre) }
        val moodConstraintStems = NlpText.stemTokens(moodConstraintText).toSet()
        val requiredMoods = moods.filterTo(HashSet()) {
            it.matchStrength(moodConstraintStems, moodConstraintText) > 0.0 ||
                (moodStrengths[it] ?: 1.0) < 0.5
        }
        val genreTags = index.knownGenres.filterTo(HashSet()) {
            GenreTaxonomy.resolve(it) == null && GenreTaxonomy.containsPhrase(descriptorText, it)
        }
        val slotStems = moods.flatMap { it.triggerStems + it.normalizedPhrases.flatMap(NlpText::stemTokens) }.toSet() +
            requiredGenres.flatMap { it.queryAliases.flatMap(NlpText::stemTokens) } +
            genreTags.flatMap(NlpText::stemTokens)

        // "Something like Radiohead": only activates when a seed artist actually resolves in
        // the library, so an ambiguous cue like "like"/"como" in "I like happy songs" leaves
        // the query untouched (half-activating similarity mode would be worse than not trying).
        val similarToArtist = seed?.normalizedArtist
        val similarSeedGenreStems: Set<String> = if (seed != null) {
            index.songs.asSequence()
                .filter { it.normalizedArtist == seed.normalizedArtist }
                .flatMapTo(HashSet()) { it.genreStems }
        } else {
            emptySet()
        }

        return ParsedQuery(
            normalizedQuery, contentStems, termWeights, yearRange, matchedGenreStems, moods, targetBpm,
            moodStrengths = moodStrengths,
            negatedStems = negatedStems,
            lexicalNegatedStems = negatedStems - resolvedNegativeStems,
            similarToArtist = similarToArtist,
            similarSeedGenreStems = similarSeedGenreStems,
            queryGenreFamilies = queryGenreFamilies,
            requiredArtists = requiredArtists,
            excludedArtists = excludedArtists,
            requiredGenres = requiredGenres,
            excludedGenres = excludedGenres,
            requiredGenreTags = genreTags,
            excludedYearRanges = excludedYearRanges,
            strictSelection = strictSelection,
            requiredTextStems = if (strictSelection) descriptorStems - slotStems else emptySet(),
            requiredMoods = requiredMoods,
            requiresMeasuredBpm = textBpm != null,
        )
    }

    /** Recognize complete local names before grammar: No Doubt and Death Cab for Cutie contain
     * words that otherwise open or close negation. Partial names never reserve a token span. */
    private fun artistTokenIndices(tokens: List<String>, index: LibraryIndex): Set<Int> {
        val protected = HashSet<Int>()
        val names = index.songs.asSequence().flatMap { it.artistNames.asSequence() }.distinct()
        for (name in names) {
            if (NlpLexicon.isStopWord(name) || NlpLexicon.isNegationCue(name) || name in STRICT_CUES || name.length < 2) continue
            val words = SCOPE_TOKENS.findAll(name).map { it.value }.toList()
            if (words.isEmpty() || words.size > tokens.size) continue
            for (start in 0..tokens.size - words.size) {
                if (words.indices.all { words[it] == tokens[start + it] }) {
                    words.indices.forEach { protected.add(start + it) }
                }
            }
        }
        return protected
    }

    /** Match whole local identities, longest first, so Muse does not resolve inside Muse Tribute.
     * Multiple named artists are alternatives; other slots (year, genre, mood) remain conjunctive. */
    private fun resolveArtists(text: String, index: LibraryIndex): Set<String> {
        var remaining = text
        val result = HashSet<String>()
        for (artist in index.songs.asSequence().flatMap { it.artistNames.asSequence() }
            .filter { !NlpLexicon.isStopWord(it) && it.length >= 2 }
            .distinct().sortedByDescending { it.length }) {
            if (GenreTaxonomy.containsPhrase(remaining, artist)) {
                result.add(artist)
                remaining = GenreTaxonomy.maskPhrase(remaining, artist)
            }
        }
        return result
    }

    /**
     * Genre families implied by a query's words when no curated alias matched. Weak rows are
     * dropped at [CONCEPT_FAMILY_MIN_WEIGHT]; an empty result simply means the request has no
     * family reading, exactly as before the table existed.
     *
     * Limitation: this is bag-of-words, so it cannot compose. "heavy rain" reads `heavy` as the
     * guitar family even though the phrase is about weather — the matched SAD mood's energy band
     * keeps the result sane, but the table cannot tell the two readings apart on its own.
     */
    private fun inferredFamilies(contentStems: Set<String>, concepts: ConceptTable): Set<GenreFamily> {
        var result: MutableSet<GenreFamily>? = null
        for (stem in contentStems) {
            val families = concepts.lookup(stem)?.families ?: continue
            for ((family, weight) in families) {
                if (weight < CONCEPT_FAMILY_MIN_WEIGHT) continue
                (result ?: HashSet<GenreFamily>().also { result = it }).add(family)
            }
        }
        return result ?: emptySet()
    }

    /**
     * Resolves the "seed" artist for a "like X" / "similar to X" query: finds the rightmost
     * similarity cue in [normalizedQuery], takes the substring after it, and matches it against
     * the library's artist names (exact match first, else containment). Returns null when no
     * cue is present or no artist resolves, so the caller can fall back to normal query handling
     * instead of half-activating similarity mode.
     */
    private fun resolveSeedArtist(normalizedQuery: String, index: LibraryIndex): IndexedSong? {
        val cueEnd = NlpLexicon.similarityPhrases
            .mapNotNull { cue ->
                val idx = normalizedQuery.indexOf(cue)
                if (idx < 0) null else idx + cue.length
            }
            .maxOrNull() ?: return null

        val after = normalizedQuery.substring(cueEnd).trim()
        if (after.isEmpty()) return null

        index.songs.firstOrNull { it.normalizedArtist.isNotEmpty() && it.normalizedArtist == after }
            ?.let { return it }

        return index.songs
            .filter {
                it.normalizedArtist.isNotEmpty() &&
                    (it.normalizedArtist.contains(after) || after.contains(it.normalizedArtist))
            }
            .maxByOrNull { it.normalizedArtist.length }
    }

    /**
     * Produces an ordered list of song ids. Fixed inputs, context and [random] seed reproduce
     * both selection and sequencing; ordinary calls vary within the relevant candidate pool.
     */
    fun generate(
        query: String,
        minLength: Int,
        maxLength: Int,
        index: LibraryIndex,
        engagements: Map<String, SongEngagementStats>,
        random: Random = Random.Default,
        bpmBySongId: Map<String, Float> = emptyMap(),
        vibeBySongId: Map<String, SongVibe> = emptyMap(),
        context: MixContext = MixContext(),
        weights: MixWeights = MixWeights(),
        concepts: ConceptTable = ConceptTable.EMPTY,
    ): List<String> {
        val shortlist = shortlist(
            query, minLength, maxLength, index, engagements, random,
            bpmBySongId, vibeBySongId, context, weights, concepts,
        ) ?: return emptyList()
        return OfflineMixRanker.rank(
            shortlist.candidates, index, engagements, shortlist.targetSize, random,
            bpmBySongId, vibeBySongId, shortlist.context, weights,
        )
    }

    /**
     * The eligible, relevance-scored candidates [generate] hands to [OfflineMixRanker]. Consumes
     * [random] exactly as [generate] does before ranking, so shortlist + rank reproduces
     * [generate] for a fixed seed.
     */
    class Shortlist(
        val candidates: List<OfflineMixRanker.Candidate>,
        val context: MixContext,
        val targetSize: Int,
    )

    fun shortlist(
        query: String,
        minLength: Int,
        maxLength: Int,
        index: LibraryIndex,
        engagements: Map<String, SongEngagementStats>,
        random: Random = Random.Default,
        bpmBySongId: Map<String, Float> = emptyMap(),
        vibeBySongId: Map<String, SongVibe> = emptyMap(),
        context: MixContext = MixContext(),
        weights: MixWeights = MixWeights(),
        concepts: ConceptTable = ConceptTable.EMPTY,
    ): Shortlist? {
        if (index.songs.isEmpty()) return null

        val parsed = parse(query, index, concepts)
        val seedIds = context.seedIds.ifEmpty {
            if (parsed.similarToArtist != null) index.songs.filter {
                it.normalizedArtist == parsed.similarToArtist
            }.mapTo(HashSet()) { it.song.id }
            else resolveContentSeeds(parsed.normalizedQuery, index)
        }
        val effectiveContext = context.copy(seedIds = seedIds)

        val recentlyAdded: Set<String> = if (MoodProfile.RECENT in parsed.moods) {
            index.songs.asSequence()
                .sortedByDescending { it.song.dateAdded }
                .take(RECENT_POOL_SIZE)
                .mapTo(HashSet()) { it.song.id }
        } else {
            emptySet()
        }

        // Eligibility precedes ranking: all required slots need evidence. An incidental title
        // hit, favorite, playlist relationship or high mood score cannot replace another slot.
        val eligible = index.songs.mapNotNull { indexed ->
            val playCount = engagements[indexed.song.id]?.playCount ?: 0
            if (!satisfiesRequirements(indexed, parsed, playCount, recentlyAdded, bpmBySongId, vibeBySongId)
                || (parsed.yearRange != null && indexed.song.year !in parsed.yearRange)
                || parsed.excludedYearRanges.any { years -> indexed.song.year in years }
                || indexed.artistNames.any { artist -> artist in parsed.excludedArtists }
                || parsed.excludedGenres.any { genre -> GenreTaxonomy.matchesGenre(indexed.evidenceGenre, genre) }
                || parsed.lexicalNegatedStems.any { stem -> stem in indexed.artistStems ||
                    stem in indexed.titleStems || stem in indexed.albumStems || stem in indexed.genreStems }
                || indexed.song.id in seedIds) return@mapNotNull null
            Triple(indexed, scoreSong(indexed, parsed, playCount, recentlyAdded, bpmBySongId, vibeBySongId), playCount)
        }

        val seedSongs = index.songs.filter { it.song.id in seedIds }.take(weights.maxSeeds)
        val playlistNeighbors = context.playlists.filter { playlist -> playlist.any { it in seedIds } }.flatten().toSet()
        val candidates = if (seedIds.isNotEmpty()) eligible.filter { candidate ->
            candidate.first.song.id in playlistNeighbors || seedSongs.any {
                OfflineMixRanker.similarity(candidate.first, it, bpmBySongId, vibeBySongId, weights.similarity) >= weights.minimumSeedSimilarity
            }
        } else if (parsed.hasSelectionSignal || parsed.hasEnergySignal) {
            eligible.filter { it.second.isFinite() && it.second > 0.0 }
        } else {
            eligible
        }

        return Shortlist(
            candidates = candidates.map { OfflineMixRanker.Candidate(it.first, it.second) },
            context = effectiveContext,
            targetSize = targetSize(candidates.size, minLength, maxLength, random, weights.maxTracks),
        )
    }

    private fun satisfiesRequirements(
        song: IndexedSong, query: ParsedQuery, playCount: Int, recentlyAdded: Set<String>,
        bpm: Map<String, Float>, vibes: Map<String, SongVibe>,
    ): Boolean {
        if (query.requiredArtists.isNotEmpty() && song.artistNames.none { it in query.requiredArtists }) return false
        if (query.excludedGenres.isNotEmpty() && song.genreFamily == null) return false
        if (query.excludedArtists.isNotEmpty() && song.artistNames.isEmpty()) return false
        if (query.excludedYearRanges.isNotEmpty() && song.song.year <= 0) return false
        // "only rock" is a claim about the files themselves, so a strict request accepts just the
        // song's own tag; otherwise a genre inherited from its artist is evidence enough.
        val genreEvidence = if (query.strictSelection) song.normalizedGenre else song.evidenceGenre
        if ((query.requiredGenres.isNotEmpty() || query.requiredGenreTags.isNotEmpty()) &&
            query.requiredGenres.none { GenreTaxonomy.matchesGenre(genreEvidence, it) } &&
            query.requiredGenreTags.none { GenreTaxonomy.containsPhrase(song.normalizedGenre, it) }) return false
        if (query.requiredTextStems.any { it !in song.titleStems && it !in song.albumStems &&
                (song.artistNames.isEmpty() || it !in song.artistStems) }) return false
        // Even an inferred family needs actual genre evidence, rather than a coincidental title.
        if (query.queryGenreFamilies.isNotEmpty() && song.genreFamily !in query.queryGenreFamilies) return false
        val measuredBpm = bpm[song.song.id]?.takeIf { it.isFinite() && it in 20f..400f }
        if (query.targetBpm != null) {
            val tempo = measuredBpm?.toDouble() ?: if (!query.requiresMeasuredBpm) {
                song.genreEnergy?.let(::genreEnergyToBpm)
            } else null
            val tolerance = if (measuredBpm != null) BPM_MISMATCH_THRESHOLD else GENRE_ENERGY_MISMATCH_THRESHOLD
            if (tempo == null || kotlin.math.abs(tempo - query.targetBpm) > tolerance) return false
        }
        val vibe = validVibe(vibes[song.song.id])
        for (mood in query.requiredMoods) {
            when (mood.kind) {
                MoodKind.GENRE -> {
                    val supported = if (vibe != null) {
                        vibeDistance(vibe, mood)?.let { it <= VIBE_MISMATCH_THRESHOLD } == true
                    } else if (song.genreEnergy != null) {
                        song.genreEnergy in mood.minEnergy..mood.maxEnergy
                    } else {
                        measuredBpm != null && mood.preferredBpm?.let {
                            kotlin.math.abs(measuredBpm - it) <= BPM_MISMATCH_THRESHOLD
                        } == true
                    }
                    if (!supported) return false
                }
                MoodKind.FAVORITES -> if (query.strictSelection && !song.song.isFavorite) return false
                MoodKind.DISCOVERY -> if (query.strictSelection && playCount != 0) return false
                MoodKind.RECENT -> if (query.strictSelection && song.song.id !in recentlyAdded) return false
            }
        }
        return true
    }

    private fun validVibe(vibe: SongVibe?): SongVibe? = vibe?.takeIf {
        it.energy in 0f..1f && it.brightness in 0f..1f && it.dynamics in 0f..1f && it.percussiveness in 0f..1f
    }

    /** Exact title/album seeds only: fuzzy titles easily resolve to the wrong recording. */
    private fun resolveContentSeeds(query: String, index: LibraryIndex): Set<String> {
        val cue = NlpLexicon.similarityPhrases.mapNotNull {
            val position = query.indexOf(it)
            if (position < 0) null else position + it.length
        }.maxOrNull() ?: return emptySet()
        val name = query.substring(cue).trim()
        if (name.isEmpty()) return emptySet()
        val titleMatches = index.songs.filter { it.normalizedTitle == name }
        val matches = titleMatches.ifEmpty { index.songs.filter { it.normalizedAlbum == name } }
        return matches.mapTo(HashSet()) { it.song.id }
    }

    private fun scoreSong(
        indexed: IndexedSong,
        parsed: ParsedQuery,
        playCount: Int,
        recentlyAdded: Set<String>,
        bpmBySongId: Map<String, Float>,
        vibeBySongId: Map<String, SongVibe> = emptyMap(),
    ): Double {
        val song = indexed.song
        var score = 0.0

        // Decade.
        if (parsed.yearRange != null && song.year in parsed.yearRange) {
            score += DECADE_BONUS
        }

        // Genre slot (stem intersection covers exact + fuzzy singular/plural matches).
        if (parsed.matchedGenreStems.isNotEmpty() &&
            indexed.genreStems.any { it in parsed.matchedGenreStems }
        ) {
            score += GENRE_BONUS
        }

        // Reward family similarity inside the admitted set (for example, grunge for rock).
        // The concrete genre slot has already rejected incompatible siblings such as metal.
        if (parsed.queryGenreFamilies.isNotEmpty() &&
            indexed.genreFamily in parsed.queryGenreFamilies
        ) {
            score += GENRE_FAMILY_BONUS
        }

        // Free-text terms, weighted by rarity so distinctive words dominate common ones.
        for (stem in parsed.contentStems) {
            val weight = parsed.termWeights[stem] ?: 1.0
            if (stem in indexed.artistStems) score += ARTIST_BASE * weight
            if (stem in indexed.titleStems) score += TITLE_BASE * weight
            if (stem in indexed.albumStems) score += ALBUM_BASE * weight
        }

        if (parsed.similarToArtist != null) {
            // Similarity mode: reward the seed artist's genre neighborhood, exclude the seed's
            // own tracks (the user said "like", implying not the same thing), and suppress the
            // literal exact-artist bonus below — it would just re-add the seed artist's songs.
            if (indexed.normalizedArtist == parsed.similarToArtist) {
                score += SIMILARITY_SEED_EXCLUSION
            }
            if (indexed.genreStems.any { it in parsed.similarSeedGenreStems }) {
                score += SIMILARITY_GENRE_BONUS
            }
        } else {
            if (indexed.artistNames.any { it in parsed.requiredArtists }) {
                score += EXACT_ARTIST_BONUS
            }
        }

        // Mood / profile bonuses, weighted by trigger confidence and capped so a query naming
        // several moods can't out-score one specific, strongly-matched mood.
        var moodBonus = 0.0
        for (mood in parsed.moods) {
            val strength = parsed.moodStrengths[mood] ?: 1.0
            val kindBonus = when (mood.kind) {
                MoodKind.GENRE -> if (mood in indexed.genreMoods) mood.bonus else 0.0
                MoodKind.FAVORITES -> if (song.isFavorite) mood.bonus else 0.0
                MoodKind.DISCOVERY -> if (playCount == 0) mood.bonus else 0.0
                MoodKind.RECENT -> if (song.id in recentlyAdded) mood.bonus else 0.0
            }
            moodBonus += kindBonus * strength
            // Mood "extras" (a long track reads as chill; a familiar track as a workout staple)
            // are tie-breakers *among songs that already fit the mood* — gate them behind an
            // actual genre-family match. Otherwise a long metal track collects the CHILL bonus,
            // and a beloved ballad collects the WORKOUT one, which is exactly how heavy songs
            // used to leak into a "chillout" request when the library skews heavy.
            if (kindBonus > 0.0) {
                when (mood) {
                    MoodProfile.CHILL -> if (song.duration > CHILL_LONG_DURATION_MS) score += CHILL_DURATION_BONUS
                    MoodProfile.WORKOUT -> if (playCount > WORKOUT_FAMILIARITY_MIN_PLAYS) score += WORKOUT_FAMILIARITY_BONUS
                    else -> Unit
                }
            }
        }
        score += moodBonus.coerceAtMost(MAX_TOTAL_MOOD_BONUS)

        score += energyScore(indexed, parsed, bpmBySongId, vibeBySongId)

        return score
    }

    /**
     * Tempo/energy contribution: proximity to the target BPM (measured beats genre-derived),
     * plus a per-matched-GENRE-mood term that is either a measured-vibe proximity bonus (when
     * the song has real audio features) or the coarse genre-energy conflict penalty (the
     * original heuristic, used as a fallback). Genre energy — not measured tempo — drives the
     * fallback conflict check, because a slow metal track is still not "chill": timbre lives in
     * the genre, tempo in the BPM.
     */
    private fun energyScore(
        indexed: IndexedSong,
        parsed: ParsedQuery,
        bpmBySongId: Map<String, Float>,
        vibeBySongId: Map<String, SongVibe> = emptyMap(),
    ): Double {
        var score = 0.0
        val measuredBpm = bpmBySongId[indexed.song.id]?.takeIf { it.isFinite() && it in 20f..400f }
        val genreEnergy = indexed.genreEnergy
        val vibe = validVibe(vibeBySongId[indexed.song.id])

        val targetBpm = parsed.effectiveTargetBpm
        if (targetBpm != null) {
            if (measuredBpm != null) {
                score += bpmAffinity(
                    measuredBpm.toDouble(), targetBpm.toDouble(),
                    BPM_MATCH_MAX, BPM_SIGMA, BPM_MISMATCH_THRESHOLD, BPM_MISMATCH_PENALTY,
                )
            } else if (genreEnergy != null && parsed.targetBpm != null) {
                // Only an *explicit* slider target justifies reading a genre tag as a tempo:
                // that control is labelled "Music Energy", so mapping genre energy onto its
                // scale compares like with like. A mood's implied [MoodProfile.preferredBpm] is
                // a real tempo hint and must not be used this way — WORKOUT's 150 back-maps to
                // energy 0.75, which put pop (0.70 -> 144) *closer to target than metal*
                // (0.92 -> 170) and ranked Taylor Swift above Linkin Park for a "heavy" prompt.
                // Moods score on the energy axis below instead.
                score += bpmAffinity(
                    genreEnergyToBpm(genreEnergy), parsed.targetBpm.toDouble(),
                    GENRE_ENERGY_MATCH_MAX, GENRE_ENERGY_SIGMA,
                    GENRE_ENERGY_MISMATCH_THRESHOLD, GENRE_ENERGY_MISMATCH_PENALTY,
                )
            }
        }

        for (mood in parsed.moods) {
            if (mood.kind != MoodKind.GENRE) continue
            if (vibe != null) {
                // Measured audio beats the coarse genre-string guess: a song can have a wrong
                // or empty genre tag and still be correctly identified as chill by its vibe.
                score += vibeAffinity(vibe, mood)
            } else if (genreEnergy != null) {
                if (genreEnergy < mood.minEnergy || genreEnergy > mood.maxEnergy) {
                    score += MOOD_ENERGY_CONFLICT_PENALTY
                } else {
                    score += genreEnergyAffinity(genreEnergy, mood)
                }
            }
        }

        return score
    }

    /**
     * Coarse counterpart to [vibeAffinity] for songs with no measured audio: how close a song's
     * genre energy sits to the mood's [MoodProfile.targetEnergy], on the energy axis the two
     * numbers actually share. Reward only — the out-of-band case is already a hard penalty in
     * [energyScore] and an outright exclusion in [satisfiesRequirements] for explicit moods.
     *
     * Without this, an in-band song got *nothing* from the mood's energy preference, so every
     * genre inside WORKOUT's wide 0.55..1.0 band scored identically and the mix was decided by
     * whatever tie-break came next. This is what makes a "heavy" request prefer metal to pop
     * rather than treating them as interchangeable.
     */
    private fun genreEnergyAffinity(genreEnergy: Double, mood: MoodProfile): Double {
        val target = mood.targetEnergy?.toDouble() ?: return 0.0
        val distance = kotlin.math.abs(genreEnergy - target)
        val ratio = distance / GENRE_ENERGY_AXIS_SIGMA
        return GENRE_ENERGY_MATCH_MAX * kotlin.math.exp(-ratio * ratio)
    }

    /**
     * Proximity bonus between a song's measured [vibe] and a matched GENRE [mood]'s target
     * vector: Euclidean distance over whichever features the mood defines, mapped through a
     * gaussian (mirrors [bpmAffinity]'s shape). Returns 0 for moods with no target vector
     * (behavioral profiles never reach here since they're not [MoodKind.GENRE]).
     */
    private fun vibeAffinity(vibe: SongVibe, mood: MoodProfile): Double {
        val distance = vibeDistance(vibe, mood) ?: return 0.0
        var affinity = VIBE_MATCH_MAX * kotlin.math.exp(-(distance / VIBE_SIGMA) * (distance / VIBE_SIGMA))
        if (distance > VIBE_MISMATCH_THRESHOLD) affinity += VIBE_MISMATCH_PENALTY
        return affinity
    }

    /** Euclidean distance between a measured [vibe] and a [mood]'s target vector, over whichever
     *  features the mood defines; null when the mood has no target vector (behavioral profiles). */
    private fun vibeDistance(vibe: SongVibe, mood: MoodProfile): Double? {
        var sumSquares = 0.0
        var dimensions = 0
        mood.targetEnergy?.let { sumSquares += diffSquared(vibe.energy, it); dimensions++ }
        mood.targetBrightness?.let { sumSquares += diffSquared(vibe.brightness, it); dimensions++ }
        mood.targetDynamics?.let { sumSquares += diffSquared(vibe.dynamics, it); dimensions++ }
        mood.targetPercussiveness?.let { sumSquares += diffSquared(vibe.percussiveness, it); dimensions++ }
        if (dimensions == 0) return null
        return kotlin.math.sqrt(sumSquares)
    }

    private fun diffSquared(actual: Float, target: Float): Double {
        val diff = (actual - target).toDouble()
        return diff * diff
    }

    /** Gaussian proximity bonus plus a hard penalty beyond [mismatchThreshold] BPM away. */
    private fun bpmAffinity(
        bpm: Double,
        target: Double,
        maxBonus: Double,
        sigma: Double,
        mismatchThreshold: Double,
        mismatchPenalty: Double,
    ): Double {
        val distance = kotlin.math.abs(bpm - target)
        var affinity = maxBonus * kotlin.math.exp(-(distance / sigma) * (distance / sigma))
        if (distance > mismatchThreshold) affinity += mismatchPenalty
        return affinity
    }

    /** Maps lexicon energy (0..1) onto the UI's 60–180 BPM slider scale. */
    private fun genreEnergyToBpm(energy: Double): Double = 60.0 + energy * BPM_PER_ENERGY_UNIT

    /** Chooses a final size within [minLength, maxLength], clamped to what is available. */
    private fun targetSize(available: Int, minLength: Int, maxLength: Int, random: Random, cap: Int): Int {
        val low = minOf(minLength, maxLength).coerceIn(0, cap)
        val high = maxOf(minLength, maxLength).coerceIn(low, cap)
        if (available <= low) return available
        val size = if (low == high) low else random.nextInt(low, high + 1)
        return size.coerceAtMost(available)
    }

    /**
     * Decades (`90s`, `1990s`, `nineties`, `noventa`) span ten years; bare four-digit years are
     * exact filters.
     * Two-digit values 00–30 map to the 2000s, otherwise the 1900s.
     */
    private fun decadeRangeOf(token: String): IntRange? {
        // Spelled-out first: "nineties" is a decade, not a content word.
        NlpLexicon.spelledDecadeStart(token)?.let { return it..(it + 9) }
        NlpLexicon.SHORT_DECADE.find(token)?.let { match ->
            val shortDecade = match.groupValues[1].toIntOrNull() ?: return null
            val start = if (shortDecade in 0..30) 2000 + shortDecade else 1900 + shortDecade
            return start..(start + 9)
        }
        NlpLexicon.FULL_DECADE.find(token)?.let { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return null
            if (year > 1900) return if (token.endsWith("s")) year..(year + 9) else year..year
        }
        return null
    }
}
