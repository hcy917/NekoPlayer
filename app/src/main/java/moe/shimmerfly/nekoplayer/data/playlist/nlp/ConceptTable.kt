package moe.shimmerfly.nekoplayer.data.playlist.nlp

/**
 * What a single query word implies about the taxonomy the scorer already speaks: which
 * [GenreFamily] super-families it points at, and which [MoodProfile]s it evokes. Weights are
 * `0..1` confidences, sparse — a word only carries the entries it actually implies.
 */
data class ConceptWeights(
    val families: Map<GenreFamily, Double> = emptyMap(),
    val moods: Map<MoodProfile, Double> = emptyMap(),
) {
    val isEmpty: Boolean get() = families.isEmpty() && moods.isEmpty()
}

/**
 * A precomputed lookup that lets the parser understand music vocabulary nobody hand-listed.
 *
 * Every SmartMix accuracy bug so far has been the same shape: a word absent from
 * [MoodProfile.synonyms] or [GenreTaxonomy.queryAliases], so the engine had no idea what the
 * user meant. `"heavy"` was one — it existed only as a WORKOUT energy cue, whose families span
 * pop and dance, so a `heavy` prompt came back full of pop. Hand-adding words one bug at a time
 * does not converge; there is always another word ("brutal", "dreamy", "bangers", "pesado").
 *
 * So the curated lists stay authoritative and this table generalizes *past* them: a table of
 * `word -> (families, moods)` authored offline (see THIRD_PARTY_NOTICES.md) and shipped as a
 * ~200KB text asset. Deliberately **not** a runtime model — the target space is this project's
 * own 12 families and 7 moods, which is a knowledge lookup, not an inference problem. Nothing
 * here allocates per-query beyond the lookup itself.
 *
 * Keyed by **stem**, through the same [NlpText.normalize] + [NlpText.stem] pipeline as the
 * query and the library, so it composes with every existing match path. [EMPTY] is a working
 * table that knows nothing, which is what every caller gets if the asset is missing or
 * unparseable — the engine degrades to its hand-written behavior rather than failing.
 */
class ConceptTable private constructor(private val byStem: Map<String, ConceptWeights>) {

    val size: Int get() = byStem.size

    /** Weights for an already-normalized, already-stemmed token, or null when unknown. */
    fun lookup(stem: String): ConceptWeights? = byStem[stem]

    /** Convenience for callers holding a raw word (tests, tooling); prefer [lookup] on the hot path. */
    fun lookupWord(word: String): ConceptWeights? = lookup(NlpText.stem(NlpText.normalize(word)))

    companion object {
        /** A table that knows nothing. Every lookup misses, so the engine keeps today's behavior. */
        val EMPTY = ConceptTable(emptyMap())

        private const val FIELD_SEPARATOR = '|'
        private const val ENTRY_SEPARATOR = ','
        private const val WEIGHT_SEPARATOR = ':'

        /** Asset weights are authored as integer percents to keep the file diffable. */
        private const val WEIGHT_SCALE = 100.0

        private val FAMILIES_BY_NAME = GenreFamily.entries.associateBy { it.name }
        private val MOODS_BY_NAME = MoodProfile.entries.associateBy { it.name }

        /**
         * Parses the `concepts.txt` asset format: one concept per line, `#` starting a comment,
         * blank lines ignored.
         *
         * ```
         * heavy|GUITAR:95|WORKOUT:60
         * dreamy|CHILL_AMBIENT:70,GUITAR:25|CHILL:80
         * bailable||HAPPY:85
         * ```
         *
         * Unparseable lines and unrecognized family/mood names are **skipped, not fatal**: the
         * asset outlives the code that reads it, so an enum renamed later must cost us one row,
         * not the whole table. Two words that stem alike (`"heavy"`/`"heavies"`) merge by taking
         * the stronger weight, so the result does not depend on line order.
         */
        fun parse(lines: Sequence<String>): ConceptTable {
            val merged = HashMap<String, ConceptWeights>()
            for (raw in lines) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val fields = line.split(FIELD_SEPARATOR)
                if (fields.size < 3) continue

                val stem = NlpText.stem(NlpText.normalize(fields[0].trim()))
                if (stem.isEmpty()) continue

                val families = parseWeights(fields[1], FAMILIES_BY_NAME)
                val moods = parseWeights(fields[2], MOODS_BY_NAME)
                if (families.isEmpty() && moods.isEmpty()) continue

                val existing = merged[stem]
                merged[stem] = if (existing == null) {
                    ConceptWeights(families, moods)
                } else {
                    ConceptWeights(
                        families = mergeByMax(existing.families, families),
                        moods = mergeByMax(existing.moods, moods),
                    )
                }
            }
            return if (merged.isEmpty()) EMPTY else ConceptTable(merged)
        }

        private fun <K> parseWeights(field: String, byName: Map<String, K>): Map<K, Double> {
            val trimmed = field.trim()
            if (trimmed.isEmpty()) return emptyMap()
            val result = HashMap<K, Double>()
            for (entry in trimmed.split(ENTRY_SEPARATOR)) {
                val text = entry.trim()
                if (text.isEmpty()) continue
                val key = byName[text.substringBefore(WEIGHT_SEPARATOR).trim().uppercase()] ?: continue
                val weight = text.substringAfter(WEIGHT_SEPARATOR, "").trim().toDoubleOrNull() ?: continue
                val normalized = (weight / WEIGHT_SCALE).coerceIn(0.0, 1.0)
                if (normalized <= 0.0) continue
                result[key] = maxOf(result[key] ?: 0.0, normalized)
            }
            return result
        }

        private fun <K> mergeByMax(a: Map<K, Double>, b: Map<K, Double>): Map<K, Double> {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            val result = HashMap<K, Double>(a)
            for ((key, value) in b) result[key] = maxOf(result[key] ?: 0.0, value)
            return result
        }
    }
}
