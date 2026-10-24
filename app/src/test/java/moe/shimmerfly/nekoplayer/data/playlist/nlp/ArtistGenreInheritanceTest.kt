package moe.shimmerfly.nekoplayer.data.playlist.nlp

import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.data.model.Song
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * A song with no usable genre tag used to be invisible to every mood request: offline, the tag
 * is the only evidence of what a song sounds like. These cover the two ways such a song now
 * borrows a genre from its artist — the same artist's tagged songs, then [ArtistGenreTable].
 */
class ArtistGenreInheritanceTest {

    private fun song(id: String, artist: String, genre: String?, title: String = "Track $id"): Song =
        Song.emptySong().copy(id = id, title = title, artist = artist, genre = genre)

    private fun table(vararg rows: String) = ArtistGenreTable.parse(rows.asSequence())

    private fun mix(query: String, index: LibraryIndex) = PlaylistIntentEngine.generate(
        query = query, minLength = 10, maxLength = 10, index = index,
        engagements = emptyMap(), random = Random(0),
    )

    private fun LibraryIndex.song(id: String) = songs.first { it.song.id == id }

    @Test
    fun `an untagged song inherits the genre of the same artist's tagged songs`() {
        val index = LibraryIndex.build(
            listOf(
                song("lp_tagged", "Linkin Park", "Nu Metal"),
                song("lp_untagged", "Linkin Park", null),
                song("lp_junk_tag", "Linkin Park", "Other"),
            )
        )

        assertThat(index.song("lp_untagged").genreFamily).isEqualTo(GenreFamily.GUITAR)
        assertThat(index.song("lp_junk_tag").genreFamily).isEqualTo(GenreFamily.GUITAR)
        // The raw tag is untouched: inheritance is evidence, not a rewrite of the file's metadata.
        assertThat(index.song("lp_untagged").normalizedGenre).isEmpty()
        assertThat(index.song("lp_junk_tag").normalizedGenre).isEqualTo("other")
    }

    @Test
    fun `a song's own recognized tag is never overridden`() {
        val index = LibraryIndex.build(
            listOf(
                song("metal_1", "Opeth", "Death Metal"),
                song("metal_2", "Opeth", "Death Metal"),
                song("acoustic", "Opeth", "Acoustic"),
            ),
            artistGenres = table("opeth|pop"),
        )

        assertThat(index.song("acoustic").genreFamily).isEqualTo(GenreFamily.FOLK_COUNTRY)
        assertThat(index.song("acoustic").inferredGenre).isEmpty()
    }

    @Test
    fun `an artist with evenly split tags lends nothing`() {
        val index = LibraryIndex.build(
            listOf(
                song("a", "Chameleon", "Metal"),
                song("b", "Chameleon", "Ambient"),
                song("c", "Chameleon", "Jazz"),
                song("untagged", "Chameleon", null),
            )
        )

        assertThat(index.song("untagged").genreFamily).isNull()
    }

    @Test
    fun `the bundled table fills in when the library has no tag for the artist`() {
        val index = LibraryIndex.build(
            listOf(song("bmth", "Bring Me The Horizon", null)),
            artistGenres = table("bring me the horizon|metalcore;alternative rock;deathcore"),
        )

        assertThat(index.song("bmth").genreFamily).isEqualTo(GenreFamily.GUITAR)
    }

    @Test
    fun `the user's own tags for an artist beat the bundled table`() {
        val index = LibraryIndex.build(
            listOf(song("tagged", "Moby", "Ambient"), song("untagged", "Moby", null)),
            artistGenres = table("moby|techno"),
        )

        assertThat(index.song("untagged").genreFamily).isEqualTo(GenreFamily.CHILL_AMBIENT)
    }

    @Test
    fun `unknown artists stay unclassified`() {
        val index = LibraryIndex.build(
            listOf(song("mystery", "Garage Band Nobody Knows", null), song("blank", "", null)),
            artistGenres = table("linkin park|nu metal"),
        )

        assertThat(index.song("mystery").genreFamily).isNull()
        assertThat(index.song("blank").genreFamily).isNull()
    }

    @Test
    fun `a rebuilt index re-evaluates inheritance for reused entries`() {
        val untagged = song("untagged", "Linkin Park", null)
        val before = LibraryIndex.build(listOf(untagged))
        assertThat(before.song("untagged").genreFamily).isNull()

        val after = LibraryIndex.build(listOf(untagged, song("tagged", "Linkin Park", "Nu Metal")), before)
        assertThat(after.song("untagged").genreFamily).isEqualTo(GenreFamily.GUITAR)

        val reverted = LibraryIndex.build(listOf(untagged), after)
        assertThat(reverted.song("untagged").genreFamily).isNull()
    }

    @Test
    fun `running finds untagged songs by high-energy artists`() {
        val index = LibraryIndex.build(
            listOf(
                song("lp_tagged", "Linkin Park", "Nu Metal"),
                song("lp_untagged", "Linkin Park", null),
                song("bmth", "Bring Me The Horizon", null),
                song("ambient", "Brian Eno", null),
                song("mystery", "Garage Band Nobody Knows", null),
            ),
            artistGenres = table("bring me the horizon|metalcore", "brian eno|ambient"),
        )

        assertThat(mix("running", index)).containsExactly("lp_tagged", "lp_untagged", "bmth")
    }

    @Test
    fun `a chill request still excludes untagged songs by heavy artists`() {
        val index = LibraryIndex.build(
            listOf(
                song("bmth", "Bring Me The Horizon", null),
                song("eno", "Brian Eno", null),
            ),
            artistGenres = table("bring me the horizon|metalcore", "brian eno|ambient"),
        )

        assertThat(mix("chillout", index)).containsExactly("eno")
    }

    @Test
    fun `explicit genre requests and exclusions see inherited genres`() {
        val index = LibraryIndex.build(
            listOf(
                song("lp", "Linkin Park", null),
                song("eno", "Brian Eno", null),
                song("pop", "Taylor Swift", "Pop"),
            ),
            artistGenres = table("linkin park|nu metal", "brian eno|ambient"),
        )

        assertThat(mix("metal", index)).containsExactly("lp")
        assertThat(mix("pop without metal", index)).containsExactly("pop")
    }

    @Test
    fun `a strict genre request accepts only the song's own tag`() {
        val index = LibraryIndex.build(
            listOf(song("inherited", "Linkin Park", null), song("tagged", "Slipknot", "Nu Metal")),
            artistGenres = table("linkin park|nu metal"),
        )

        assertThat(mix("only metal", index)).containsExactly("tagged")
    }

    @Test
    fun `a real tag outranks an inherited one for the same genre request`() {
        val index = LibraryIndex.build(
            listOf(song("inherited", "Linkin Park", null), song("tagged", "Slipknot", "Nu Metal")),
            artistGenres = table("linkin park|nu metal"),
        )

        assertThat(mix("nu metal", index)).containsExactly("tagged", "inherited").inOrder()
    }
}
