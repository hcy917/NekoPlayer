package moe.shimmerfly.nekoplayer.data.playlist.nlp

import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.data.model.Song
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Covers the [ConceptTable] format and its gap-filling contract inside the parser, using a
 * synthetic table so these tests say nothing about the shipped asset's quality — only about the
 * wiring. Anchor assertions against the real asset live in `ConceptAssetTest`.
 */
class ConceptTableTest {

    private fun table(vararg lines: String) = ConceptTable.parse(lines.asSequence())

    private fun song(id: String, artist: String, genre: String): Song =
        Song.emptySong().copy(id = id, title = id, artist = artist, genre = genre)

    // --- format ---------------------------------------------------------------------------

    @Test
    fun `parses families, moods and sparse fields`() {
        val parsed = table(
            "# comment line",
            "",
            "heavy|GUITAR:95|WORKOUT:60",
            "bailable||HAPPY:85",
            "dreamy|CHILL_AMBIENT:70,GUITAR:25|",
        )

        assertThat(parsed.size).isEqualTo(3)
        assertThat(parsed.lookupWord("heavy")!!.families).containsExactly(GenreFamily.GUITAR, 0.95)
        assertThat(parsed.lookupWord("heavy")!!.moods).containsExactly(MoodProfile.WORKOUT, 0.60)
        assertThat(parsed.lookupWord("bailable")!!.families).isEmpty()
        assertThat(parsed.lookupWord("dreamy")!!.families).hasSize(2)
        assertThat(parsed.lookupWord("dreamy")!!.moods).isEmpty()
    }

    @Test
    fun `lookup goes through the same normalize and stem pipeline as the query`() {
        val parsed = table("energico|GUITAR:80|WORKOUT:70")
        // Accented input and inflections must still hit the row.
        assertThat(parsed.lookupWord("energico")).isNotNull()
        assertThat(parsed.lookupWord("ENERGICO")).isNotNull()
        assertThat(parsed.lookupWord("enérgico")).isNotNull()
    }

    @Test
    fun `a bad row costs one row, never the table`() {
        val parsed = table(
            "heavy|GUITAR:95|WORKOUT:60",
            "broken-no-fields",
            "unknownfam|NOT_A_FAMILY:90|",
            "unknownmood||NOT_A_MOOD:90",
            "badweight|GUITAR:abc|",
            "zeroed|GUITAR:0|",
            "cozy|CHILL_AMBIENT:80|CHILL:75",
        )

        // Only the two well-formed rows survive; nothing throws.
        assertThat(parsed.size).isEqualTo(2)
        assertThat(parsed.lookupWord("heavy")).isNotNull()
        assertThat(parsed.lookupWord("cozy")).isNotNull()
    }

    @Test
    fun `words that stem alike merge by strongest weight regardless of order`() {
        val ascending = table("banger|URBAN:40|", "bangers|URBAN:90|")
        val descending = table("bangers|URBAN:90|", "banger|URBAN:40|")

        assertThat(ascending.size).isEqualTo(1)
        assertThat(ascending.lookupWord("bangers")!!.families[GenreFamily.URBAN]).isEqualTo(0.90)
        assertThat(descending.lookupWord("banger")!!.families[GenreFamily.URBAN]).isEqualTo(0.90)
    }

    @Test
    fun `an empty or unreadable asset leaves the engine exactly as it was`() {
        assertThat(ConceptTable.parse(emptySequence()).size).isEqualTo(0)
        assertThat(ConceptTable.parse(sequenceOf("garbage", "")).lookupWord("heavy")).isNull()
        assertThat(ConceptTable.EMPTY.lookupWord("heavy")).isNull()
    }

    // --- gap filling in the parser ----------------------------------------------------------

    private val library = listOf(
        song("metal", "Gojira", "Death Metal"),
        song("rock", "Foo Fighters", "Rock"),
        song("pop", "Dua Lipa", "Pop"),
        song("ambient", "Hammock", "Ambient"),
    )
    private val index = LibraryIndex.build(library)

    @Test
    fun `an unlisted word gains a genre family it could not have had before`() {
        val concepts = table("brutal|GUITAR:90|WORKOUT:70")

        assertThat(PlaylistIntentEngine.parse("brutal", index).queryGenreFamilies).isEmpty()
        assertThat(PlaylistIntentEngine.parse("brutal", index, concepts).queryGenreFamilies)
            .containsExactly(GenreFamily.GUITAR)
    }

    @Test
    fun `curated aliases win outright and are never widened by the table`() {
        // A deliberately wrong row: "rock" is a curated GUITAR alias, so the table must not
        // get a vote at all — otherwise one bad row could silently redirect a known genre.
        val concepts = table("rock|LATIN:99|HAPPY:99")
        val parsed = PlaylistIntentEngine.parse("rock", index, concepts)

        assertThat(parsed.queryGenreFamilies).containsExactly(GenreFamily.GUITAR)
    }

    @Test
    fun `a curated mood hit outranks an inferred one on the same word`() {
        // "chill" is a curated CHILL synonym at single-word strength 0.5; a table row claiming
        // full confidence must not push it higher or add a competing mood for that same word.
        val concepts = table("chill|GUITAR:99|WORKOUT:99")
        val parsed = PlaylistIntentEngine.parse("chill", index, concepts)

        assertThat(parsed.moodStrengths[MoodProfile.CHILL]).isEqualTo(0.5)
        assertThat(parsed.moods).doesNotContain(MoodProfile.WORKOUT)
    }

    @Test
    fun `an inferred mood stays weaker than a literal synonym hit`() {
        val concepts = table("cozy||CHILL:100")
        val inferred = PlaylistIntentEngine.parse("cozy", index, concepts)
        val literal = PlaylistIntentEngine.parse("relax", index)

        assertThat(inferred.moodStrengths[MoodProfile.CHILL]).isNotNull()
        assertThat(inferred.moodStrengths[MoodProfile.CHILL]!!)
            .isLessThan(literal.moodStrengths[MoodProfile.CHILL]!!)
    }

    @Test
    fun `low-confidence rows are ignored on both axes`() {
        val concepts = table("vibes|GUITAR:30|WORKOUT:20")
        val parsed = PlaylistIntentEngine.parse("vibes", index, concepts)

        assertThat(parsed.queryGenreFamilies).isEmpty()
        assertThat(parsed.moods).isEmpty()
    }

    @Test
    fun `an inferred family never hides a band whose name is an ordinary music word`() {
        // "Train" and "Jungle" are real acts and also ordinary words in the table's vocabulary.
        // Searching one must return it, even though the inferred family would exclude its genre.
        val bands = listOf(
            song("train_pop", "Train", "Pop Rock"),
            song("jungle_funk", "Jungle", "Funk"),
            song("unrelated", "Hammock", "Ambient"),
        )
        val bandIndex = LibraryIndex.build(bands)
        val concepts = table("train|CHILL_AMBIENT:80|CHILL:70", "jungle|ELECTRONIC:90|")

        assertThat(
            PlaylistIntentEngine.generate(
                query = "train", minLength = 3, maxLength = 3, index = bandIndex,
                engagements = emptyMap(), random = Random(0), concepts = concepts,
            )
        ).contains("train_pop")

        assertThat(
            PlaylistIntentEngine.generate(
                query = "jungle", minLength = 3, maxLength = 3, index = bandIndex,
                engagements = emptyMap(), random = Random(0), concepts = concepts,
            )
        ).contains("jungle_funk")
    }

    @Test
    fun `a curated genre alias keeps filtering even when it matches an artist name`() {
        // The stand-down is only for *inferred* concepts. "metal" is a curated alias, so a band
        // literally called Metal Church does not disable genre filtering for everyone else.
        val bands = listOf(
            song("mc", "Metal Church", "Heavy Metal"),
            song("pop", "Dua Lipa", "Pop"),
        )
        val bandIndex = LibraryIndex.build(bands)
        val concepts = table("metal|CHILL_AMBIENT:99|CHILL:99")

        assertThat(PlaylistIntentEngine.parse("metal", bandIndex, concepts).queryGenreFamilies)
            .containsExactly(GenreFamily.GUITAR)
        assertThat(
            PlaylistIntentEngine.generate(
                query = "metal", minLength = 2, maxLength = 2, index = bandIndex,
                engagements = emptyMap(), random = Random(0), concepts = concepts,
            )
        ).doesNotContain("pop")
    }

    @Test
    fun `the table stands down entirely when the query names an artist`() {
        val bands = listOf(song("train_pop", "Train", "Pop Rock"), song("amb", "Hammock", "Ambient"))
        val bandIndex = LibraryIndex.build(bands)
        val concepts = table("train|CHILL_AMBIENT:80|CHILL:70")

        // Neither axis may fire: not the family exclusion, and not the mood's energy band.
        val parsed = PlaylistIntentEngine.parse("train", bandIndex, concepts)
        assertThat(parsed.queryGenreFamilies).isEmpty()
        assertThat(parsed.moods).isEmpty()
    }

    @Test
    fun `an inferred family turns an unanswerable request into a correct mix`() {
        val concepts = table("brutal|GUITAR:90|WORKOUT:70")

        val without = PlaylistIntentEngine.generate(
            query = "brutal", minLength = 4, maxLength = 4, index = index,
            engagements = emptyMap(), random = Random(0),
        )
        val with = PlaylistIntentEngine.generate(
            query = "brutal", minLength = 4, maxLength = 4, index = index,
            engagements = emptyMap(), random = Random(0), concepts = concepts,
        )

        // This is the *other* user-visible shape of the same hole. A word in no curated list
        // resolves to nothing at all, and `generate` correctly refuses to pad an unresolved
        // request with most-played songs — so the mix comes back empty. ("heavy" took the other
        // path: it *was* in a mood list, so it produced a confidently wrong mix instead.)
        assertThat(without).isEmpty()
        assertThat(with).containsExactly("metal", "rock")
    }
}
