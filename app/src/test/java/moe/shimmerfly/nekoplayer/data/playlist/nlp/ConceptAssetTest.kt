package moe.shimmerfly.nekoplayer.data.playlist.nlp

import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.data.model.Song
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

/**
 * Asserts things about the **shipped** `concepts.txt` asset, as opposed to [ConceptTableTest],
 * which covers the format and wiring with synthetic tables.
 *
 * These are anchor assertions: a small set of words whose correct labelling is not a matter of
 * taste. The asset is machine-generated (see THIRD_PARTY_NOTICES.md), so this is the guard that a
 * regeneration cannot quietly ship a table that no longer understands the vocabulary the feature
 * was fixed for. **If one of these fails, fix the asset, not the assertion.**
 *
 * Read from the filesystem rather than through `Context.assets`, since this is a plain JVM test;
 * Gradle runs unit tests with the module directory as the working directory.
 */
class ConceptAssetTest {

    private val assetFile: File = sequenceOf(
        File("src/main/assets/nlp/concepts.txt"),
        File("app/src/main/assets/nlp/concepts.txt"),
    ).firstOrNull { it.isFile } ?: error(
        "concepts.txt not found (cwd=${File(".").absolutePath})."
    )

    private val table: ConceptTable =
        assetFile.bufferedReader().use { ConceptTable.parse(it.lineSequence()) }

    private fun familyWeight(word: String, family: GenreFamily): Double =
        table.lookupWord(word)?.families?.get(family) ?: 0.0

    private fun moodWeight(word: String, mood: MoodProfile): Double =
        table.lookupWord(word)?.moods?.get(mood) ?: 0.0

    /** The threshold `PlaylistIntentEngine` requires before trusting an inferred family. */
    private val familyTrustThreshold = 0.6

    @Test
    fun `the asset is present, parses, and is not trivially small`() {
        assertThat(table.size).isAtLeast(200)
        // The whole premise is that this stays a small text file, not a model.
        assertThat(assetFile.length()).isLessThan(1_000_000)
    }

    @Test
    fun `the reported bug word resolves to guitar music`() {
        assertThat(familyWeight("heavy", GenreFamily.GUITAR)).isAtLeast(familyTrustThreshold)
    }

    @Test
    fun `heaviness vocabulary resolves to guitar music in both languages`() {
        for (word in listOf("heavy", "brutal", "riff", "pesado", "distorsionado")) {
            assertThat(familyWeight(word, GenreFamily.GUITAR)).isAtLeast(familyTrustThreshold)
        }
    }

    @Test
    fun `feeling words carry a mood and claim no genre family`() {
        // Overreach here is the expensive mistake: a family on a pure feeling word would hard-
        // exclude every other kind of music from a request that never named a genre.
        for (word in listOf("aching", "gloomy", "heartache", "tristeza")) {
            assertThat(moodWeight(word, MoodProfile.SAD)).isAtLeast(0.5)
            val families = table.lookupWord(word)?.families.orEmpty()
            assertThat(families.filterValues { it >= familyTrustThreshold }).isEmpty()
        }
    }

    @Test
    fun `calm and upbeat vocabulary lands on the right mood in both languages`() {
        for (word in listOf("cozy", "soothing", "acogedor", "somnoliento")) {
            assertThat(moodWeight(word, MoodProfile.CHILL)).isAtLeast(0.5)
        }
        for (word in listOf("euphoric", "bailable", "festive", "fiestero")) {
            assertThat(moodWeight(word, MoodProfile.HAPPY)).isAtLeast(0.5)
        }
    }

    @Test
    fun `no row can exceed full confidence`() {
        // Guarded by the parser, asserted here against real generated data.
        assertThat(assetFile.readLines().count { it.startsWith("#") }).isAtLeast(1)
        for (line in assetFile.readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val word = line.substringBefore('|')
            val weights = table.lookupWord(word) ?: continue
            for (weight in weights.families.values + weights.moods.values) {
                assertThat(weight).isGreaterThan(0.0)
                assertThat(weight).isAtMost(1.0)
            }
        }
    }

    // --- end to end, with the real asset ----------------------------------------------------

    private fun song(id: String, artist: String, genre: String): Song =
        Song.emptySong().copy(id = id, title = id, artist = artist, genre = genre)

    @Test
    fun `the real asset fixes the reported query end to end`() {
        val library = listOf(
            song("lp_numetal", "Linkin Park", "Nu Metal"),
            song("lp_rock", "Linkin Park", "Rock"),
            song("ts_pop", "Taylor Swift", "Pop"),
            song("dnce_pop", "DNCE", "Dance Pop"),
        )
        val index = LibraryIndex.build(library)

        // "brutal" is in no curated list at all, so without the asset this request is
        // unanswerable and returns nothing; with it, it resolves to the guitar family.
        assertThat(
            PlaylistIntentEngine.generate(
                query = "brutal", minLength = 4, maxLength = 4, index = index,
                engagements = emptyMap(), random = Random(0),
            )
        ).isEmpty()

        assertThat(
            PlaylistIntentEngine.generate(
                query = "brutal", minLength = 4, maxLength = 4, index = index,
                engagements = emptyMap(), random = Random(0), concepts = table,
            )
        ).containsExactly("lp_numetal", "lp_rock")
    }
}
