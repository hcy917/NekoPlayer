package moe.shimmerfly.nekoplayer.data.playlist.nlp

import moe.shimmerfly.nekoplayer.data.DailyMixManager.SongEngagementStats
import moe.shimmerfly.nekoplayer.data.model.Song
import moe.shimmerfly.nekoplayer.data.model.ArtistRef
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random
import java.io.File

/** Adversarial fixtures from the local diagnostic report, including sparse metadata. */
class PlaylistPrecisionTest {
    private fun song(id: String, artist: String = "Act $id", genre: String? = null, year: Int = 1995) =
        Song.emptySong().copy(id = id, title = "Track $id", artist = artist, genre = genre,
            album = "Album $artist", year = year, duration = 180_000)

    private fun mix(query: String, songs: List<Song>, count: Int = 20, seed: Int = 42,
        bpm: Map<String, Float> = emptyMap(), vibes: Map<String, SongVibe> = emptyMap(),
        context: MixContext = MixContext(), stats: Map<String, SongEngagementStats> = emptyMap()) =
        PlaylistIntentEngine.generate(query, count, count, LibraryIndex.build(songs), stats,
            Random(seed), bpmBySongId = bpm, vibeBySongId = vibes, context = context)

    @Test fun `bare bring me the horizon name is an artist request with automatic energy and shipped concepts`() {
        val band = (0 until 10).map { song("bmth$it", "Bring Me The Horizon", "Metalcore") }
        val unrelated = listOf(song("linkin", "Linkin Park", "Nu Metal"),
            song("dnce", "DNCE", "Dance Pop").copy(title = "Bring Me Home", isFavorite = true),
            song("weeknd", "The Weeknd", "R&B").copy(album = "Horizon", isFavorite = true))
        val songs = band + unrelated
        val index = LibraryIndex.build(songs)
        val asset = sequenceOf(File("src/main/assets/nlp/concepts.txt"), File("app/src/main/assets/nlp/concepts.txt"))
            .first { it.isFile }
        val concepts = asset.bufferedReader().use { ConceptTable.parse(it.lineSequence()) }
        for (query in listOf("bring me the horizon", SmartMixRequest.prompt("bring me the horizon", selectedLength = 25),
            SmartMixRequest.prompt("bring me the horizon", selectedEnergyBpm = 160, selectedLength = 25))) {
            val parsed = PlaylistIntentEngine.parse(query, index, concepts)
            assertEquals(setOf("bring me the horizon"), parsed.requiredArtists)
            assertNull(parsed.similarToArtist)
            for (seed in 0..9) {
                val ids = PlaylistIntentEngine.generate(query, 25, 25, index, emptyMap(), Random(seed), concepts = concepts)
                assertEquals(band.map { it.id }.toSet(), ids.toSet())
            }
        }
    }

    @Test fun `explicit prompt tempo overrides a selected UI energy target`() {
        val songs = listOf(song("match", "Bring Me The Horizon", "Rock"),
            song("wrong", "Bring Me The Horizon", "Rock"), song("other", "DNCE", "Pop"))
        val query = SmartMixRequest.prompt("bring me the horizon at 80 bpm", selectedEnergyBpm = 160)
        val parsed = PlaylistIntentEngine.parse(query, LibraryIndex.build(songs))
        assertEquals(80f, parsed.targetBpm)
        assertTrue(parsed.requiresMeasuredBpm)
        assertEquals(listOf("match"), mix(query, songs, bpm = mapOf("match" to 82f, "wrong" to 160f, "other" to 80f)))
        assertNull(PlaylistIntentEngine.parse(SmartMixRequest.prompt("bring me the horizon"), LibraryIndex.build(songs)).targetBpm)
    }

    @Test fun `only metal never uses other guitar genres to fill or diversify`() {
        val metal = (0 until 20).map { song("m$it", "Metal Band", "Metal") }
        val rock = (0 until 30).map { song("r$it", genre = "Rock").copy(isFavorite = true) }
        for (seed in 0..9) {
            assertEquals(metal.map { it.id }.toSet(), mix("only metal", metal + rock, seed = seed).toSet())
        }
        assertEquals(3, mix("only metal", metal.take(3) + rock).size)
    }

    @Test fun `metal requirement resolves even when no metal tags exist in library`() {
        val songs = listOf(song("rock", genre = "Rock").copy(title = "Metal workout", isFavorite = true))
        assertTrue(mix("only metal", songs).isEmpty())
        assertTrue(mix("metal", songs).isEmpty())
    }

    @Test fun `metal umbrella accepts metal subgenres without absorbing punk or rock`() {
        val songs = listOf(song("core", genre = "Metalcore"), song("djent", genre = "Djent"),
            song("nu", genre = "Nu Metal"), song("punk", genre = "Punk"), song("rock", genre = "Rock"))
        assertEquals(setOf("core", "djent", "nu"), mix("only metal", songs).toSet())
        assertEquals(listOf("core"), mix("only metalcore", songs))
    }

    @Test fun `artist plus workout is conjunctive and returns a short playlist`() {
        val artist = (0 until 10).map { song("a$it", "Radiohead", "Rock") }
        val others = (0 until 30).map { song("b$it", genre = "Rock").copy(isFavorite = true) }
        // Untagged, but ten of this artist's eleven tagged songs are Rock, so it inherits that.
        val untagged = song("untagged", "Radiohead").copy(title = "Workout anthem")
        val calm = song("calm", "Radiohead", "Ambient")
        for (query in listOf("only Radiohead for a workout", "Radiohead for a workout", "solo Radiohead para entrenar")) {
            for (seed in 0..4) {
                assertEquals((artist + untagged).map { it.id }.toSet(), mix(query, artist + others + untagged + calm, seed = seed).toSet())
            }
        }
    }

    @Test fun `artist matching uses complete identities and masks mood words in names`() {
        val songs = listOf(song("muse", "Muse", "Rock"), song("tribute", "Muse Tribute", "Rock"),
            song("train", "Train", "Ambient"), song("acdc", "AC/DC", "Rock"))
        assertEquals(listOf("muse"), mix("only Muse", songs))
        assertEquals(listOf("tribute"), mix("only Muse Tribute", songs))
        assertEquals(listOf("train"), mix("only Train", songs))
        assertEquals(listOf("acdc"), mix("only AC/DC for a workout", songs))
    }

    @Test fun `multiple named artists form alternatives in one required slot`() {
        val songs = listOf(song("a", "Radiohead", "Rock"), song("b", "Muse", "Rock"),
            song("c", "Other", "Rock"))
        assertEquals(setOf("a", "b"), mix("only Radiohead or Muse for a workout", songs).toSet())
    }

    @Test fun `artist names protect negation cues and prepositions`() {
        val songs = listOf(song("doubt", "No Doubt", "Rock"), song("cab", "Death Cab for Cutie", "Rock"),
            song("other", "Other", "Rock"))
        assertEquals(listOf("doubt"), mix("only No Doubt for a workout", songs))
        assertEquals(listOf("cab"), mix("only Death Cab for Cutie", songs))
        assertEquals(listOf("other"), mix("rock without No Doubt or Death Cab for Cutie", songs))
    }

    @Test fun `individual artist credits are exact evidence for collaborations`() {
        val collaboration = song("collab", "Other", "Rock").copy(artists = listOf(
            ArtistRef(1, "Other", true), ArtistRef(2, "Radiohead")))
        val songs = listOf(collaboration, song("other", "Other", "Rock"))
        assertEquals(listOf("collab"), mix("only Radiohead for a workout", songs))
        assertEquals(listOf("other"), mix("rock without Radiohead", songs))
    }

    @Test fun `typed exclusions do not reject incidental title or artist word overlaps`() {
        val songs = listOf(song("band", "Linkin Park", "Rock"),
            song("title", "Other", "Rock").copy(title = "A Walk in the Park"),
            song("tribute", "Linkin Park Tribute", "Rock"),
            song("metalTitle", "Another", "Rock").copy(title = "Metal Heart"))
        assertEquals(setOf("title", "tribute", "metalTitle"),
            mix("rock without Linkin Park or metal", songs).toSet())
    }

    @Test fun `named genres do not impose an unrequested measured mood`() {
        val songs = listOf(song("jazz", genre = "Jazz"))
        val intense = mapOf("jazz" to SongVibe(.9f, .7f, .4f, .8f))
        assertEquals(listOf("jazz"), mix("only jazz", songs, vibes = intense))
        assertTrue(mix("only chill jazz", songs, vibes = intense).isEmpty())
    }

    @Test fun `narrow genre phrases do not widen to their constituent nouns`() {
        val songs = listOf(song("post", genre = "Post-Rock"), song("rock", genre = "Rock"),
            song("popPunk", genre = "Pop Punk"), song("pop", genre = "Pop"), song("punk", genre = "Punk"),
            song("alternativeMetal", genre = "Alternative Metal"))
        assertEquals(listOf("post"), mix("only post rock", songs))
        assertEquals(listOf("popPunk"), mix("only pop punk", songs))
        assertFalse("alternativeMetal" in mix("only rock", songs))
    }

    @Test fun `single tagged pop track remains eligible`() {
        val songs = listOf(song("pop", "Jane Doe", "Pop").copy(title = "Happy Beats", album = "Pop Album"))
        val index = LibraryIndex.build(songs)
        val parsed = PlaylistIntentEngine.parse("pop", index)
        assertEquals(setOf(GenreFamily.POP), parsed.queryGenreFamilies, parsed.toString())
        assertTrue(parsed.requiredArtists.isEmpty(), parsed.toString())
        assertTrue(parsed.moods.isEmpty(), parsed.toString())
        assertEquals("pop", index.songs.single().normalizedGenre)
        assertEquals(GenreFamily.POP, index.songs.single().genreFamily)
        assertTrue(parsed.requiredGenres.all { GenreTaxonomy.matchesGenre("pop", it) }, parsed.toString())
        assertEquals(listOf("pop"), mix("pop", songs))
    }

    @Test fun `90s rock without metal or punk keeps all exclusions in scope`() {
        val rock = (0 until 10).map { song("r$it", genre = "Rock") }
        val excluded = (0 until 10).flatMap { listOf(song("m$it", genre = "Metal"), song("p$it", genre = "Punk")) }
        for (query in listOf("90s rock without metal or punk", "90s rock without metal, punk",
            "rock de los noventa sin metal o punk")) {
            assertEquals(rock.map { it.id }.toSet(), mix(query, rock + excluded).toSet())
            val parsed = PlaylistIntentEngine.parse(query, LibraryIndex.build(rock + excluded))
            assertTrue(NlpText.stem("punk") in parsed.negatedStems)
            assertFalse(NlpText.stem("punk") in parsed.contentStems)
            assertFalse(MoodProfile.WORKOUT in parsed.moods)
        }
    }

    @Test fun `genre exclusions recognize implicit subgenres and mixed genre tags`() {
        val songs = listOf(song("rock", genre = "Rock"), song("grunge", genre = "Grunge"),
            song("core", genre = "Metalcore"), song("mixed", genre = "Rock / Pop Punk"),
            song("djent", genre = "Djent"))
        assertEquals(setOf("rock", "grunge"), mix("90s rock without metal or punk", songs).toSet())
    }

    @Test fun `positive clause ends negation and multiword mood phrases stay suppressed`() {
        val index = LibraryIndex.build(listOf(song("a", "Linkin Park", "Metal")))
        val parsed = PlaylistIntentEngine.parse("without Linkin Park or feel good but chill", index)
        assertTrue("linkin park" in parsed.excludedArtists)
        assertEquals(setOf(MoodProfile.CHILL), parsed.moods)
        assertFalse(NlpText.stem("chill") in parsed.negatedStems)
        val activity = PlaylistIntentEngine.parse("rock without metal or punk for a workout", index)
        assertTrue(MoodProfile.WORKOUT in activity.moods)
    }

    @Test fun `unknown mood attributes and lexical coincidences cannot satisfy evidence`() {
        val songs = (0 until 20).map { song("u$it").copy(title = "Chill workout $it", isFavorite = true) }
        assertTrue(mix("chill songs", songs).isEmpty())
        assertTrue(mix("only chill", songs).isEmpty())
        assertTrue(mix("only workout", songs).isEmpty())
        assertTrue(mix("only MissingArtist for a workout", listOf(song("rock", genre = "Rock"))).isEmpty())
        // Unconstrained discovery remains available for sparse libraries.
        assertEquals(20, mix("", songs).size)
    }

    @Test fun `valid measured evidence admits untagged songs while invalid values stay unknown`() {
        val songs = listOf(song("valid"), song("nan"), song("range"), song("unknown"))
        val vibes = mapOf("valid" to SongVibe(.2f, .3f, .5f, .2f),
            "nan" to SongVibe(Float.NaN, .3f, .5f, .2f), "range" to SongVibe(-1f, .3f, .5f, .2f))
        assertEquals(listOf("valid"), mix("only chill", songs, vibes = vibes))
        assertEquals(listOf("valid"), mix("only chill", songs, bpm = mapOf("valid" to 80f, "nan" to Float.NaN)))
    }

    @Test fun `exact tempo requires measured evidence and cannot use a genre guess`() {
        val songs = listOf(song("match", genre = "Rock"), song("wrong", genre = "Rock"),
            song("guess", genre = "Rock"), song("unknown"))
        assertEquals(listOf("match"), mix("only rock at 120 bpm", songs,
            bpm = mapOf("match" to 120f, "wrong" to 200f, "unknown" to Float.POSITIVE_INFINITY)))
        assertTrue(mix("120 bpm", songs).isEmpty())
    }

    @Test fun `exact year genre and artist each require independent evidence`() {
        val songs = listOf(song("match", "Radiohead", "Rock"), song("year", "Radiohead", "Rock", 0),
            song("genre", "Radiohead"), song("artist", "Other", "Rock"), song("otherYear", "Radiohead", "Rock", 1996))
        assertEquals(listOf("match"), mix("only Radiohead rock from 1995", songs))
        assertEquals(listOf("otherYear"), mix("only Radiohead rock without 1995", songs))
    }

    @Test fun `playlist relationships never bypass an explicit genre constraint`() {
        val songs = listOf(song("seed", "Seed", "Metal"), song("metal", genre = "Metal"),
            song("rock", genre = "Rock"))
        val context = MixContext(seedIds = setOf("seed"), playlists = listOf(setOf("seed", "rock")))
        assertEquals(listOf("metal"), mix("only metal", songs, context = context))
    }

    @Test fun `taste randomness repetition and adjacency cannot displace a stronger relevance band`() {
        val strong = (0 until 12).map { song("s$it", "One Artist", "Metal") }
        val weak = (0 until 12).map { song("w$it", genre = "Metal").copy(isFavorite = true) }
        val index = LibraryIndex.build(strong + weak)
        val candidates = index.songs.map { OfflineMixRanker.Candidate(it, if (it.song.id.startsWith("s")) 80.0 else 1.0) }
        val stats = weak.associate { it.id to SongEngagementStats(100, 18_000_000) }
        for (seed in 0..9) {
            val ids = OfflineMixRanker.rank(candidates, index, stats, 12, Random(seed),
                context = MixContext(recentMixIds = strong.map { it.id }.toSet()))
            assertEquals(strong.map { it.id }.toSet(), ids.toSet())
        }
    }
}
