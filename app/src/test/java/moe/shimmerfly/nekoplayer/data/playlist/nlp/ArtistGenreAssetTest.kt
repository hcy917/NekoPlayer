package moe.shimmerfly.nekoplayer.data.playlist.nlp

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Asserts things about the **shipped** `artist-genres.txt` asset, as opposed to
 * [ArtistGenreTableTest], which covers the format with synthetic rows.
 *
 * Anchor assertions only: acts whose genre family is not a matter of taste. The asset is
 * generated from Wikidata (see THIRD_PARTY_NOTICES.md), so this guards against a regeneration
 * quietly shipping a table that lost them. **If one fails, fix the asset or the taxonomy, not
 * the assertion.**
 */
class ArtistGenreAssetTest {

    private val assetFile: File = sequenceOf(
        File("src/main/assets/nlp/artist-genres.txt"),
        File("app/src/main/assets/nlp/artist-genres.txt"),
    ).firstOrNull { it.isFile } ?: error(
        "artist-genres.txt not found (cwd=${File(".").absolutePath})."
    )

    private val table: ArtistGenreTable =
        assetFile.bufferedReader().use { ArtistGenreTable.parse(it.lineSequence()) }

    private fun familyOf(artist: String): GenreFamily? =
        table.genreFor(NlpText.normalize(artist).trim())?.let(GenreTaxonomy::familyOf)

    private fun energyOf(artist: String): Double? =
        table.genreFor(NlpText.normalize(artist).trim())?.let(GenreTaxonomy::energyOf)

    @Test
    fun `the asset is substantial`() {
        assertThat(table.size).isAtLeast(25_000)
    }

    @Test
    fun `well-known acts land in the right family`() {
        assertThat(familyOf("Linkin Park")).isEqualTo(GenreFamily.GUITAR)
        assertThat(familyOf("Bring Me the Horizon")).isEqualTo(GenreFamily.GUITAR)
        assertThat(familyOf("Metallica")).isEqualTo(GenreFamily.GUITAR)
        assertThat(familyOf("Daft Punk")).isEqualTo(GenreFamily.ELECTRONIC)
        assertThat(familyOf("Miles Davis")).isEqualTo(GenreFamily.JAZZ)
        assertThat(familyOf("Bad Bunny")).isAnyOf(GenreFamily.LATIN, GenreFamily.URBAN)
    }

    @Test
    fun `accented names are reachable through normalization`() {
        assertThat(familyOf("Beyoncé")).isNotNull()
        assertThat(familyOf("Björk")).isNotNull()
    }

    @Test
    fun `an eclectic act with no dominant family is left unknown`() {
        // ambient, art pop, art rock, electronic: no honest single answer.
        assertThat(familyOf("Brian Eno")).isNull()
    }

    @Test
    fun `heavy acts read as workout energy and calm ones as chill`() {
        assertThat(energyOf("Linkin Park")).isAtLeast(MoodProfile.WORKOUT.minEnergy)
        assertThat(energyOf("Metallica")).isAtLeast(MoodProfile.WORKOUT.minEnergy)
        assertThat(energyOf("Miles Davis")).isAtMost(MoodProfile.CHILL.maxEnergy)
    }
}
