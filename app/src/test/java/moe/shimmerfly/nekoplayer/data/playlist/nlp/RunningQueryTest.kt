package moe.shimmerfly.nekoplayer.data.playlist.nlp

import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.data.model.Song
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Regression cover for the reported "Running" bug: a one-word `Running` prompt returned nothing
 * from a library full of Linkin Park and Bring Me The Horizon.
 *
 * `run` was a curated [MoodProfile.WORKOUT] trigger all along, but the stemmer reduced the gerund
 * to `runn` — English doubles the final consonant of a short verb before `-ing`/`-ed` — so the
 * two never met. The query fell through to plain text matching against titles.
 */
class RunningQueryTest {

    private fun song(id: String, title: String, artist: String, genre: String): Song =
        Song.emptySong().copy(id = id, title = title, artist = artist, genre = genre)

    private val library = listOf(
        song("lp_numetal", "One Step Closer", "Linkin Park", "Nu Metal"),
        song("bmth_metalcore", "Shadow Moses", "Bring Me The Horizon", "Metalcore"),
        song("ts_pop", "Shake It Off", "Taylor Swift", "Pop"),
        song("ambient", "Drift", "Cloud", "Ambient"),
    )

    private val index = LibraryIndex.build(library)

    @Test
    fun `doubled-consonant gerunds converge with their base verb`() {
        assertThat(NlpText.stem("running")).isEqualTo(NlpText.stem("run"))
        assertThat(NlpText.stem("jogging")).isEqualTo(NlpText.stem("jog"))
        assertThat(NlpText.stem("swimming")).isEqualTo(NlpText.stem("swim"))
        assertThat(NlpText.stem("pumped")).isEqualTo(NlpText.stem("pump"))
    }

    @Test
    fun `undoubling leaves genuine double letters alone`() {
        // l, s and z double in the base form itself (chill, miss, buzz).
        assertThat(NlpText.stem("chilling")).isEqualTo(NlpText.stem("chill"))
        assertThat(NlpText.stem("missing")).isEqualTo(NlpText.stem("miss"))
        assertThat(NlpText.stem("buzzing")).isEqualTo(NlpText.stem("buzz"))
    }

    @Test
    fun `running reads as a workout request`() {
        for (query in listOf("Running", "running", "music for running", "run")) {
            assertThat(PlaylistIntentEngine.parse(query, index).moods).contains(MoodProfile.WORKOUT)
        }
    }

    @Test
    fun `running returns the high-energy songs and never the ambient one`() {
        val result = PlaylistIntentEngine.generate(
            query = "Running", minLength = 5, maxLength = 5, index = index,
            engagements = emptyMap(), random = Random(0),
        )

        assertThat(result).containsAtLeast("lp_numetal", "bmth_metalcore")
        assertThat(result).doesNotContain("ambient")
    }
}
