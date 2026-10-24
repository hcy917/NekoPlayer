package moe.shimmerfly.nekoplayer.data.playlist.nlp

import moe.shimmerfly.nekoplayer.data.model.Song
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Calendar
import kotlin.random.Random

class MixSuggestionEngineTest {
    private val genres = listOf("Rock", "Pop", "Jazz", "Acoustic", "Electronic", "Metal", "Ambient", "Dance", "Soul", "Hip Hop")
    private val now = calendar(2026, Calendar.SEPTEMBER, 30, hour = 20)

    private fun calendar(year: Int, month: Int, day: Int, hour: Int) =
        Calendar.getInstance().apply { clear(); set(year, month, day, hour, 0) }

    private fun song(id: String, artist: String, genre: String?, year: Int = 1995) =
        Song.emptySong().copy(id = id, title = "Track $id", artist = artist, genre = genre,
            album = "Album $artist", year = year, duration = 200_000)

    /** Ten artists, one genre each, spread over four decades. */
    private val library: List<Song> = genres.flatMapIndexed { g, genre ->
        (0 until 20).map { i -> song("$g-$i", "Band $genre", genre, year = 1975 + (i % 4) * 10 + g % 10) }
    }

    private fun suggest(
        songs: List<Song> = library,
        plays: Map<String, MixSuggestionPlays> = emptyMap(),
        recents: List<String> = emptyList(),
        at: Calendar = now,
        canMix: (MixSuggestion) -> Boolean = { true },
        textOf: ((MixSuggestion) -> String)? = null,
    ) = if (textOf == null) MixSuggestionEngine.suggest(songs, plays, recents, at.timeInMillis, at, canMix)
        else MixSuggestionEngine.suggest(songs, plays, recents, at.timeInMillis, at, canMix, textOf)

    @Test
    fun `suggestions come from the library, most played artist first`() {
        val plays = library.filter { it.artist == "Band Jazz" }.associate { it.id to MixSuggestionPlays(12, now.timeInMillis) }
        val rows = suggest(plays = plays)
        val artists = rows.flatten().filterIsInstance<MixSuggestion.Artist>().map { it.name }
        assertEquals("Band Jazz", rows.first().filterIsInstance<MixSuggestion.Artist>().first().name)
        assertTrue(artists.all { name -> library.any { it.artist == name } })
        assertTrue(rows.all { it.size <= MixSuggestionEngine.MAX_PER_ROW })
        assertEquals(rows.flatten().size, rows.flatten().map { it.key }.toSet().size)
    }

    @Test
    fun `recent requests lead, deduplicated, and hide the same artist below`() {
        val rows = suggest(recents = listOf("Band Rock", "band rock", "Chill evening", "Older request"))
        assertEquals(listOf(MixSuggestion.Recent("Band Rock"), MixSuggestion.Recent("Chill evening")), rows.first().take(2))
        assertFalse(rows.flatten().any { it is MixSuggestion.Artist && it.name == "Band Rock" })
    }

    @Test
    fun `artists and genres too small to fill a mix are not offered`() {
        val songs = library + listOf(song("x1", "One Hit", "Polka"), song("x2", "One Hit", "Polka"))
        val flat = suggest(songs).flatten()
        assertFalse(flat.any { it is MixSuggestion.Artist && it.name == "One Hit" })
        assertFalse(flat.any { it is MixSuggestion.Genre && it.name == "Polka" })
    }

    @Test
    fun `unknown artists and genres are never suggested`() {
        val unknown = (0 until 30).map { song("u$it", "<unknown>", "Unknown") }
        val flat = suggest(library + unknown).flatten()
        assertFalse(flat.any { it is MixSuggestion.Artist && it.name == "<unknown>" })
        assertFalse(flat.any { it is MixSuggestion.Genre && it.name.equals("unknown", ignoreCase = true) })
    }

    @Test
    fun `picks are stable within a day and rotate across days`() {
        assertEquals(suggest(), suggest(at = calendar(2026, Calendar.SEPTEMBER, 30, hour = 20)))
        val days = (1..20).map { suggest(at = calendar(2026, Calendar.OCTOBER, it, hour = 20))[0] }.toSet()
        assertTrue(days.size > 1, "the artist row never rotated")
    }

    @Test
    fun `an artist not played for a month is offered to rediscover`() {
        val old = now.timeInMillis - 90L * 24 * 60 * 60 * 1000
        val plays = library.associate { it.id to MixSuggestionPlays(if (it.artist == "Band Soul") 1 else 30, if (it.artist == "Band Soul") old else now.timeInMillis) }
        val rediscover = suggest(plays = plays).flatten().filterIsInstance<MixSuggestion.Artist>().filter { it.rediscover }
        assertEquals(1, rediscover.size)
        assertTrue(rediscover.single().let { r -> plays.filterKeys { id -> library.first { it.id == id }.artist == r.name }.values.all { it.lastPlayedMs == old } })
    }

    @Test
    fun `moments follow the time of day`() {
        assertEquals(MixMoment.MORNING, MixSuggestionEngine.momentsFor(calendar(2026, Calendar.SEPTEMBER, 30, hour = 7)).first())
        assertEquals(MixMoment.STUDY, MixSuggestionEngine.momentsFor(calendar(2026, Calendar.SEPTEMBER, 30, hour = 14)).first())
        assertEquals(MixMoment.LATE_NIGHT, MixSuggestionEngine.momentsFor(calendar(2026, Calendar.SEPTEMBER, 30, hour = 2)).first())
        // Friday night.
        assertEquals(MixMoment.PARTY, MixSuggestionEngine.momentsFor(calendar(2026, Calendar.OCTOBER, 2, hour = 22)).first())
    }

    @Test
    fun `rows fill in order and never differ by more than one`() {
        for (total in 0..12) {
            val rows = MixSuggestionEngine.distribute((1..total).toList())
            assertEquals((1..total).toList(), rows.flatten())
            assertEquals(minOf(3, total), rows.size)
            if (rows.isNotEmpty()) assertTrue(rows.first().size - rows.last().size in 0..1, "$total -> ${rows.map { it.size }}")
        }
        val sizes = suggest().map { it.size }
        assertEquals(3, sizes.size)
        assertTrue(sizes.max() - sizes.min() <= 1, "uneven rows: $sizes")
    }

    @Test
    fun `suggestions that cannot produce a mix are replaced, not left as gaps`() {
        val blocked = setOf("Band Rock", "Band Pop")
        val rows = suggest(canMix = { s -> !(s is MixSuggestion.Artist && s.name in blocked) && !(s is MixSuggestion.Moment && s.moment == MixMoment.WORKOUT) })
        val flat = rows.flatten()
        assertFalse(flat.any { it is MixSuggestion.Artist && it.name in blocked })
        assertFalse(flat.any { it is MixSuggestion.Moment && it.moment == MixMoment.WORKOUT })
        assertEquals(12, flat.size)
        assertEquals(flat.size, flat.map { it.key }.toSet().size)
    }

    @Test
    fun `no pill appears twice, even when different kinds read the same`() {
        // The evening's first moment reads "WORKOUT"; a recent with that text must hide it.
        val text: (MixSuggestion) -> String = { if (it is MixSuggestion.Moment) it.moment.name.lowercase() else MixSuggestionEngine.run { (it as? MixSuggestion.Recent)?.text ?: it.key } }
        val flat = suggest(recents = listOf("Workout"), at = calendar(2026, Calendar.SEPTEMBER, 30, hour = 18), textOf = text).flatten()
        assertEquals(1, flat.count { it == MixSuggestion.Recent("Workout") || it == MixSuggestion.Moment(MixMoment.WORKOUT) })
        val rows = suggest(recents = listOf("band rock", "Band Rock ")).flatten()
        assertEquals(rows.size, rows.map { NlpText.normalize(when (it) {
            is MixSuggestion.Recent -> it.text; is MixSuggestion.Artist -> it.name; is MixSuggestion.Genre -> it.name
            is MixSuggestion.DecadeGenre -> "${it.decade} ${it.genre}"; is MixSuggestion.Moment -> it.moment.name }) }.toSet().size)
    }

    @Test
    fun `when nothing can be mixed nothing is suggested`() {
        assertTrue(suggest(canMix = { false }).isEmpty())
    }

    @Test
    fun `an empty library still gets moments`() {
        val rows = suggest(songs = emptyList())
        assertTrue(rows.flatten().isNotEmpty())
        assertTrue(rows.flatten().all { it is MixSuggestion.Moment })
    }

    // ---- Every shipped label must produce a mix in the offline engine, in every language. ----

    private fun resource(locale: String): String = sequenceOf(
        File("src/main/res/$locale/strings_smart_mix.xml"),
        File("app/src/main/res/$locale/strings_smart_mix.xml"),
    ).first { it.isFile }.readText()

    private fun string(xml: String, name: String) =
        Regex("""<string name="$name"[^>]*>([^<]+)</string>""").find(xml)!!.groupValues[1].trim()

    private fun decades(xml: String) = Regex("""<string-array name="mix_decades"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
        .find(xml)!!.groupValues[1].let { Regex("<item>([^<]+)</item>").findAll(it).map { m -> m.groupValues[1].trim() }.toList() }

    private fun mix(query: String) = PlaylistIntentEngine.generate(query, 20, 20, LibraryIndex.build(library), emptyMap(), Random(7))

    @Test
    fun `every moment label finds songs in English and Spanish`() {
        for (locale in listOf("values", "values-es")) {
            val xml = resource(locale)
            MixMoment.entries.forEach { moment ->
                val label = string(xml, "mix_moment_" + moment.name.lowercase())
                assertTrue(mix(label).isNotEmpty(), "$locale: \"$label\" found no songs")
            }
        }
    }

    @Test
    fun `decade and genre labels parse as that decade in English and Spanish`() {
        for (locale in listOf("values", "values-es")) {
            val xml = resource(locale)
            val format = string(xml, "mix_suggest_decade_genre")
            decades(xml).forEachIndexed { index, decadeLabel ->
                val decade = 1950 + index * 10
                val label = format.replace("%1\$s", decadeLabel).replace("%2\$s", "Rock")
                val intent = PlaylistIntentEngine.parse(label, LibraryIndex.build(library))
                assertEquals(decade, intent.yearRange?.first, "$locale: \"$label\" is not read as the ${decade}s")
            }
            val nineties = format.replace("%1\$s", decades(xml)[4]).replace("%2\$s", "Rock")
            val songs = mix(nineties).map { id -> library.first { it.id == id } }
            assertTrue(songs.isNotEmpty() && songs.all { it.genre == "Rock" && it.year in 1990..1999 }, "$locale: \"$nineties\"")
        }
    }
}
