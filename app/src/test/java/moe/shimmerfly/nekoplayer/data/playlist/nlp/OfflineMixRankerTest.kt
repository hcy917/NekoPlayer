package moe.shimmerfly.nekoplayer.data.playlist.nlp

import moe.shimmerfly.nekoplayer.data.DailyMixManager.SongEngagementStats
import moe.shimmerfly.nekoplayer.data.model.Song
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class OfflineMixRankerTest {
    private fun song(id: Int, artist: String = "artist$id", genre: String? = "Rock") =
        Song.emptySong().copy(id = "$id", title = "Track $id", artist = artist,
            album = "Album $artist", genre = genre, year = 1995, duration = 180_000)
    private fun mix(songs: List<Song>, query: String = "rock", count: Int = 20,
        stats: Map<String, SongEngagementStats> = emptyMap(), seed: Int = 42,
        context: MixContext = MixContext(nowMs = 1_000_000_000)) = PlaylistIntentEngine.generate(
        query, count, count, LibraryIndex.build(songs), stats, Random(seed), context = context)

    @Test fun `explicit decade and exclusion never leak through fallback`() {
        val songs = listOf(song(1).copy(year = 1985), song(2, genre = "Metal").copy(year = 1995), song(3))
        assertEquals(listOf("3"), mix(songs, "90s rock without metal"))
        assertTrue(mix(songs, "60s rock").isEmpty())
        assertTrue(mix(songs, "nonexistentzzartist").isEmpty())
    }
    @Test fun `song album artist and group seeds find their neighborhood`() {
        val songs = listOf(song(1, "Seed").copy(title = "Origin", album = "Source"),
            song(2, "Neighbor"), song(3, "Other", "Classical"))
        for (query in listOf("similar to Origin", "similar to Source", "similar to Seed")) {
            assertEquals("2", mix(songs, query, 1).single())
        }
        assertEquals("2", mix(songs, "", 1, context = MixContext(seedIds = setOf("1"))).single())
    }
    @Test fun `long listening beats repeated short auditions and legacy zero duration stays neutral`() {
        val songs = listOf(song(1), song(2), song(3))
        val stats = mapOf("1" to SongEngagementStats(20, 3_600_000),
            "2" to SongEngagementStats(20, 100_000), "3" to SongEngagementStats(20, 0))
        val result = mix(songs, count = 3, stats = stats)
        assertEquals("1", result.first())
        assertTrue(result.indexOf("3") < result.indexOf("2"))
    }
    @Test fun `favorites and local artist taste personalize unseen songs`() {
        val songs = listOf(song(1, "Liked").copy(isFavorite = true), song(2, "Liked"), song(3, "Other"))
        val stats = mapOf("1" to SongEngagementStats(20, 3_600_000))
        val index = LibraryIndex.build(songs)
        val ids = OfflineMixRanker.rank(index.songs.drop(1).map { OfflineMixRanker.Candidate(it, 10.0) },
            index, stats, 1, Random(1), weights = MixWeights(jitter = 0.0))
        assertEquals(listOf("2"), ids)
    }
    @Test fun `playlist relationships find untagged neighbors without rewarding self membership`() {
        val songs = listOf(song(1, genre = null), song(2, genre = null), song(3, genre = null))
        assertEquals(listOf("2"), mix(songs, "", 1,
            context = MixContext(seedIds = setOf("1"), playlists = listOf(setOf("1", "2")))))
    }
    @Test fun `diversity and duplicate recordings across sources`() {
        val songs = (0 until 100).map { song(it, "artist${it / 10}") }
        val duplicate = songs.first().copy(id = "navidrome:copy")
        val ids = mix(songs + songs.first() + duplicate, count = 30)
        val byId = (songs + duplicate).associateBy { it.id }
        assertEquals(30, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertFalse("0" in ids && duplicate.id in ids)
        assertTrue(ids.zipWithNext().all { (a,b) -> byId[a]!!.artist != byId[b]!!.artist })
        assertTrue(ids.map { byId[it]!!.artist }.toSet().size >= 9)
    }
    @Test fun `unknown metadata does not collapse distinct tracks or invent shared artists`() {
        val songs = (0 until 30).map { song(it, "<unknown>", null).copy(title = "", album = "", year = 0) }
        assertEquals(20, mix(songs, "").size)
        assertEquals(2, mix(songs.take(2), "").size)
        assertTrue(mix(emptyList(), "").isEmpty())
        assertTrue(mix(songs, "", count = 0).isEmpty())
    }
    @Test fun `fixed seed is reproducible and exposure makes repeat generation fresh`() {
        val songs = (0 until 300).map { song(it, "artist${it % 30}") }
        val first = mix(songs)
        assertEquals(first, mix(songs))
        assertNotEquals(first, mix(songs, seed = 43))
        val next = mix(songs, context = MixContext(recentMixIds = first.toSet()))
        assertTrue(first.toSet().intersect(next.toSet()).size <= 2)
    }
    @Test fun `metadata edit invalidates signature and unchanged tokenization is reused`() {
        val songs = listOf(song(1), song(2))
        val index = LibraryIndex.build(songs)
        for (changed in listOf(songs[0].copy(title = "new"), songs[0].copy(artist = "new"),
            songs[0].copy(album = "new"), songs[0].copy(isFavorite = true), songs[0].copy(year = 2001))) {
            val updated = LibraryIndex.build(listOf(changed, songs[1]), index)
            assertNotEquals(index.signature, updated.signature)
            assertSame(index.songs[1], updated.songs[1])
            assertEquals(changed, updated.songs[0].song)
        }
    }
    @Test fun `large library returns bounded relevant diverse results`() {
        val songs = (0 until 10_000).map { song(it, "artist${it % 200}", if (it % 2 == 0) "Rock" else "Classical") }
        val result = mix(songs, count = 50)
        assertEquals(50, result.size)
        assertTrue(result.all { it.toInt() % 2 == 0 })
        assertTrue(result.map { songs[it.toInt()].artist }.toSet().size >= 40)
    }
    @Test fun `prolific favorite artist cannot starve the candidate pool of alternatives`() {
        val songs = (0 until 1500).map {
            if (it < 1200) song(it, "Dominant").copy(isFavorite = true) else song(it)
        }
        val ids = mix(songs, count = 30)
        assertTrue(ids.count { songs[it.toInt()].artist == "Dominant" } <= 5)
        assertTrue(ids.map { songs[it.toInt()].artist }.toSet().size >= 25)
    }
    @Test fun `tempo and measured vibe contribute smooth transitions`() {
        val index = LibraryIndex.build(listOf(song(1), song(2), song(3)))
        val tempos = mapOf("1" to 100f, "2" to 103f, "3" to 175f)
        val vibes = mapOf("1" to SongVibe(.2f,.3f,.4f,.2f), "2" to SongVibe(.22f,.3f,.4f,.2f),
            "3" to SongVibe(.9f,.9f,.9f,.9f))
        assertTrue(OfflineMixRanker.similarity(index.songs[0], index.songs[1], tempos, vibes) >
            OfflineMixRanker.similarity(index.songs[0], index.songs[2], tempos, vibes))
    }
    @Test fun `recency decays and score explanations expose applied contributions`() {
        val index = LibraryIndex.build(listOf(song(1).copy(isFavorite = true), song(2)))
        val factors = mutableMapOf<String, OfflineMixRanker.Factors>()
        OfflineMixRanker.rank(index.songs.map { OfflineMixRanker.Candidate(it, 10.0) }, index,
            mapOf("1" to SongEngagementStats(1, 180_000, 1_000_000)), 2, Random(1),
            context = MixContext(nowMs = 1_000_000, recentMixIds = setOf("2")),
            explain = { id, factor -> factors[id] = factor })
        assertEquals(8.0, factors["1"]!!.recency, 0.0)
        assertEquals(16.0, factors["2"]!!.freshness, 0.0)
        assertEquals(10.0, factors["1"]!!.relevance, 0.0)
        assertTrue(factors["1"]!!.taste >= 9.0)
    }
    @Test fun `natural tempo exact years and bounded lengths are interpreted correctly`() {
        val songs = listOf(song(1), song(2).copy(year = 1996))
        assertEquals(listOf("1"), mix(songs, "1995 rock"))
        val index = LibraryIndex.build(songs)
        assertEquals(80f, PlaylistIntentEngine.parse("80 bpm", index).targetBpm)
        val ids = PlaylistIntentEngine.generate("80 bpm", 2, 2, index, emptyMap(), Random(0),
            bpmBySongId = mapOf("1" to 80f, "2" to 170f))
        assertEquals(listOf("1"), ids)
        assertEquals(2, PlaylistIntentEngine.generate("", Int.MAX_VALUE, Int.MAX_VALUE, index,
            emptyMap(), Random(0)).size)
        assertTrue(PlaylistIntentEngine.generate("chill", -2, -1, index, emptyMap(), Random(0)).isEmpty())
    }
}
