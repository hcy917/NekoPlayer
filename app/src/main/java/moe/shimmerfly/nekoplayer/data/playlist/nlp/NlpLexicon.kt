package moe.shimmerfly.nekoplayer.data.playlist.nlp

/**
 * Static, centralized vocabulary for the offline NLP engine. Mood/profile vocabulary lives
 * in [MoodProfile]; this holds the cross-cutting bits (stopwords) plus a couple of shared
 * regexes so callers don't recompile them per request.
 */
object NlpLexicon {

    /**
     * English + Spanish function words and generic "music request" filler that carry no
     * selection signal. Stored **normalized** (accent-folded, lowercased) and matched against
     * normalized query tokens *before* stemming, so we never accidentally stem a stopword
     * into a meaningful stem.
     */
    val stopWords: Set<String> = setOf(
        // English function words
        "the", "a", "an", "and", "or", "but", "if", "then", "else", "when", "at", "from",
        "by", "for", "with", "about", "against", "between", "into", "through", "during",
        "before", "after", "above", "below", "to", "of", "in", "on", "some", "any", "that",
        "this", "my", "me", "i", "you", "it", "is", "are", "want", "wanna", "gonna",
        // Spanish function words
        "de", "la", "el", "y", "en", "para", "con", "por", "un", "una", "lo", "los", "las",
        "que", "mi", "mis", "es", "algo", "del", "al", "o", "pero",
        // Generic music-request filler (both languages)
        "music", "song", "playlist", "songs", "tracks", "track", "cancion", "canciones",
        "musica", "reproducir", "play", "list", "lista", "temas", "tema", "quiero",
        "escuchar", "show", "give", "poner", "pon", "dame", "hazme", "crear", "crea",
        "make", "generate", "genera", "tunes", "vibes", "mood",
    ).mapTo(HashSet()) { NlpText.normalize(it) }

    /** Short decade markers like `90s`, `80s`, `00s`. Group 1 is the two-digit decade. */
    val SHORT_DECADE = "^(\\d{2})s$".toRegex()

    /** Full-year decade markers like `1990`, `1980s`, `2010`. Group 1 is the four-digit year. */
    val FULL_DECADE = "^(\\d{4})s?$".toRegex()

    /**
     * Spelled-out decades, normalized, mapped to the decade's first year. The digit forms are
     * handled by [SHORT_DECADE]/[FULL_DECADE], but people write the word at least as often
     * ("nineties rock", "rock de los noventa"), and without this the word is just an ordinary
     * content term that matches nothing in the library.
     */
    val spelledDecades: Map<String, Int> = mapOf(
        "fifties" to 1950, "cincuenta" to 1950, "cincuentas" to 1950,
        "sixties" to 1960, "sesenta" to 1960, "sesentas" to 1960,
        "seventies" to 1970, "setenta" to 1970, "setentas" to 1970,
        "eighties" to 1980, "ochenta" to 1980, "ochentas" to 1980,
        "nineties" to 1990, "noventa" to 1990, "noventas" to 1990,
    ).mapKeys { NlpText.normalize(it.key) }

    /** The first year of the decade a spelled-out token names, or null if it names none. */
    fun spelledDecadeStart(normalizedToken: String): Int? = spelledDecades[normalizedToken]

    /** True if a normalized token is a stopword and should be dropped from the query. */
    fun isStopWord(normalizedToken: String): Boolean = normalizedToken in stopWords

    /**
     * Cues that open a negated list or phrase, through the next positive clause boundary.
     * Stored normalized and matched before stemming.
     */
    val negationCues: Set<String> = setOf(
        "no", "not", "without", "except", "excluding", "avoid", "skip",
        "sin", "menos", "excepto", "salvo", "nada",
    ).mapTo(HashSet()) { NlpText.normalize(it) }

    /** True if a normalized token opens a negated scope. */
    fun isNegationCue(normalizedToken: String): Boolean = normalizedToken in negationCues

    /**
     * Multi-word "sounds like" cues, normalized, substring-matched against the normalized query.
     * `"like"`/`"como"` are ambiguous single words; callers must additionally require that a
     * seed artist actually resolves from the library before activating similarity mode, so
     * "I like happy songs" doesn't misfire.
     */
    val similarityPhrases: Set<String> = setOf(
        "like", "similar to", "sounds like", "kind of like", "parecido a", "similar a",
        "como", "al estilo de", "estilo",
    ).mapTo(HashSet()) { NlpText.normalize(it) }
}
