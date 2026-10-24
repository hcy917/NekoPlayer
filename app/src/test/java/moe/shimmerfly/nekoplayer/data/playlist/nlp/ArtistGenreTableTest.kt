package moe.shimmerfly.nekoplayer.data.playlist.nlp

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Format and reduction rules of [ArtistGenreTable], with synthetic rows. */
class ArtistGenreTableTest {

    private fun familyOf(table: ArtistGenreTable, artist: String) =
        table.genreFor(artist)?.let(GenreTaxonomy::familyOf)

    @Test
    fun `parses rows and skips comments, blanks and malformed lines`() {
        val table = ArtistGenreTable.parse(
            sequenceOf(
                "# header",
                "",
                "linkin park|nu metal;alternative rock",
                "no separator here",
                "|rock",
                "daft punk|house",
            )
        )

        assertThat(table.size).isEqualTo(2)
        assertThat(familyOf(table, "linkin park")).isEqualTo(GenreFamily.GUITAR)
        assertThat(familyOf(table, "daft punk")).isEqualTo(GenreFamily.ELECTRONIC)
        assertThat(table.genreFor("nobody")).isNull()
    }

    @Test
    fun `an artist with no recognizable genre is dropped`() {
        val table = ArtistGenreTable.parse(sequenceOf("someone|spoken word;field recording"))
        assertThat(table.size).isEqualTo(0)
    }

    @Test
    fun `wanted restricts what is retained`() {
        val rows = sequenceOf("linkin park|nu metal", "daft punk|house", "brian eno|ambient")

        val table = ArtistGenreTable.parse(rows, wanted = setOf("daft punk", "not in asset"))

        assertThat(table.size).isEqualTo(1)
        assertThat(table.genreFor("daft punk")).isEqualTo("house")
        assertThat(ArtistGenreTable.parse(rows, wanted = emptySet()).size).isEqualTo(0)
    }

    @Test
    fun `the majority family wins regardless of label order`() {
        val forward = ArtistGenreTable.representative(listOf("ambient", "hard rock", "punk", "grunge"))
        val backward = ArtistGenreTable.representative(listOf("grunge", "punk", "hard rock", "ambient"))

        assertThat(forward).isEqualTo(backward)
        assertThat(GenreTaxonomy.familyOf(forward!!)).isEqualTo(GenreFamily.GUITAR)
    }

    @Test
    fun `tied families resolve only when their energies are close`() {
        // country 0.55 and pop 0.70 describe a similar-energy act either way.
        assertThat(ArtistGenreTable.representative(listOf("pop", "country"))).isEqualTo("country")
        // ambient 0.25 against rock 0.80 and electronic 0.85 is a coin toss, so no answer.
        assertThat(ArtistGenreTable.representative(listOf("ambient", "art rock", "electronic"))).isNull()
    }

    @Test
    fun `within the family the median-energy label represents the artist`() {
        // shoegaze 0.50 < rock 0.80 < thrash 0.96: neither the softest nor the heaviest label.
        assertThat(ArtistGenreTable.representative(listOf("thrash", "shoegaze", "rock"))).isEqualTo("rock")
        assertThat(ArtistGenreTable.representative(listOf("unheard of"))).isNull()
    }
}
