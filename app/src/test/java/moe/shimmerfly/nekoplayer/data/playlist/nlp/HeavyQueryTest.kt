package moe.shimmerfly.nekoplayer.data.playlist.nlp

import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.data.model.Song
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Regression cover for the reported "heavy" bug: a one-word `heavy` prompt returned Linkin Park
 * *plus* Taylor Swift and DNCE. Two independent causes, one per test group below.
 *
 *  1. "heavy" named no genre family, so it read only as [MoodProfile.WORKOUT]'s energy cue —
 *     and WORKOUT's families span pop/dance/hip hop, which handed every pop track the same
 *     mood bonus a metal track got.
 *  2. With no family filter, ranking fell through to genre energy compared on the *tempo* axis,
 *     where pop (0.70 -> 144 "bpm") sits closer to WORKOUT's preferredBpm of 150 than metal
 *     (0.92 -> 170) does. Pop did not merely sneak in, it out-ranked the metal.
 */
class HeavyQueryTest {

    private fun song(id: String, title: String, artist: String, genre: String): Song =
        Song.emptySong().copy(id = id, title = title, artist = artist, genre = genre)

    private val library = listOf(
        song("lp_numetal", "One Step Closer", "Linkin Park", "Nu Metal"),
        song("lp_rock", "Faint", "Linkin Park", "Rock"),
        song("ts_pop", "Shake It Off", "Taylor Swift", "Pop"),
        song("dnce_pop", "Cake By The Ocean", "DNCE", "Dance Pop"),
        song("ambient", "Drift", "Cloud", "Ambient"),
    )

    private val index = LibraryIndex.build(library)

    @Test
    fun `a bare heavy prompt names the guitar family`() {
        val parsed = PlaylistIntentEngine.parse("heavy", index)
        assertThat(parsed.queryGenreFamilies).containsExactly(GenreFamily.GUITAR)
    }

    @Test
    fun `heavy returns guitar music only, never pop`() {
        val result = PlaylistIntentEngine.generate(
            query = "heavy", minLength = 5, maxLength = 5, index = index,
            engagements = emptyMap(), random = Random(0),
        )

        assertThat(result).containsExactly("lp_numetal", "lp_rock")
    }

    @Test
    fun `an energetic mood ranks high-energy genres above mid-energy ones`() {
        // No genre named here, so the family filter is out of the picture and this isolates the
        // energy axis: "energetic" must not prefer pop to metal the way the tempo axis did.
        val parsed = PlaylistIntentEngine.parse("energetic", index)
        assertThat(parsed.queryGenreFamilies).isEmpty()
        assertThat(parsed.moods).contains(MoodProfile.WORKOUT)

        val result = PlaylistIntentEngine.generate(
            query = "energetic", minLength = 5, maxLength = 5, index = index,
            engagements = emptyMap(), random = Random(0),
        )

        assertThat(result).contains("lp_numetal")
        assertThat(result.indexOf("lp_numetal")).isLessThan(result.indexOf("ts_pop"))
        // Ambient sits below WORKOUT's minEnergy band and stays excluded outright.
        assertThat(result).doesNotContain("ambient")
    }

    @Test
    fun `heavy metal requires metal evidence rather than the whole guitar family`() {
        val result = PlaylistIntentEngine.generate(
            query = "heavy metal", minLength = 5, maxLength = 5, index = index,
            engagements = emptyMap(), random = Random(0),
        )

        assertThat(result).containsExactly("lp_numetal")
    }
}
