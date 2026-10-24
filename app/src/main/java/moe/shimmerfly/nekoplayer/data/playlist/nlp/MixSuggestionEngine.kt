package moe.shimmerfly.nekoplayer.data.playlist.nlp

import moe.shimmerfly.nekoplayer.data.model.Song
import java.util.Calendar
import kotlin.random.Random

/** A time of day or occasion. Each label names a mood the offline engine understands. */
enum class MixMoment { MORNING, STUDY, WORKOUT, FEEL_GOOD, CHILL_EVENING, LATE_NIGHT, PARTY }

/** One composer pill. The UI renders it; tapping one fills the request with its text. */
sealed interface MixSuggestion {
    /** Stable across recompositions and days; unique within one set of suggestions. */
    val key: String

    /** Something the user asked for before (and got results). */
    data class Recent(val text: String) : MixSuggestion {
        override val key get() = "recent:" + NlpText.normalize(text)
    }

    /** An artist with enough songs to fill a mix; [rediscover] marks one not played in a while. */
    data class Artist(val name: String, val rediscover: Boolean = false) : MixSuggestion {
        override val key get() = "artist:" + NlpText.normalize(name)
    }

    data class Genre(val name: String) : MixSuggestion {
        override val key get() = "genre:" + NlpText.normalize(name)
    }

    /** A genre from one decade, e.g. 90s rock; [decade] is its first year. */
    data class DecadeGenre(val decade: Int, val genre: String) : MixSuggestion {
        override val key get() = "decade:$decade:" + NlpText.normalize(genre)
    }

    data class Moment(val moment: MixMoment) : MixSuggestion {
        override val key get() = "moment:" + moment.name
    }
}

/** Per-song listening history, as DailyMixManager records it. */
data class MixSuggestionPlays(val playCount: Int, val lastPlayedMs: Long)

/**
 * Suggestions for the Smart Mix composer, built from the user's own library and habits instead
 * of a fixed list (which offered artists the library didn't have, and moods the engine didn't
 * know). Candidates are ranked by theme:
 *
 * 1. **You**: recent requests, then the most played artists.
 * 2. **Library**: the largest genres, and a genre's strongest decade.
 * 3. **Now**: moments for the time of day, and an artist to rediscover.
 *
 * No two pills may read the same (a recent "Workout" hides the Workout moment, an artist named
 * like a genre hides the genre), judged by [textOf]. Every suggestion must produce a mix: candidates [canMix] rejects are dropped and replaced by
 * the next best one (more artists, genres or moments). The survivors fill [ROWS] rows in order,
 * as evenly as possible, so row sizes never differ by more than one. Picks rotate daily (seeded
 * by the date) among the strongest candidates, so the rows feel alive but don't reshuffle every
 * time the composer opens. Pure: no Android dependencies, safe on a background dispatcher.
 */
object MixSuggestionEngine {
    const val ROWS = 3
    const val MAX_PER_ROW = 4
    private const val MAX_RECENT = 2
    private const val REDISCOVER_AFTER_MS = 30L * 24 * 60 * 60 * 1000
    private val unknownValues = setOf("", "unknown", "unknown artist", "<unknown>", "various artists", "other", "misc")

    /**
     * [textOf] is what a pill shows (and fills in), used to keep duplicates out; the UI passes the
     * localized text. [canMix] is called lazily, only for candidates that could still make the cut.
     */
    fun suggest(
        songs: List<Song>,
        plays: Map<String, MixSuggestionPlays>,
        recentRequests: List<String>,
        nowMs: Long,
        calendar: Calendar = Calendar.getInstance().apply { timeInMillis = nowMs },
        canMix: (MixSuggestion) -> Boolean = { true },
        textOf: (MixSuggestion) -> String = ::defaultText,
    ): List<List<MixSuggestion>> {
        val random = Random(calendar.get(Calendar.YEAR) * 1000L + calendar.get(Calendar.DAY_OF_YEAR))
        // Small libraries still get suggestions, just with a lower bar for "enough songs".
        val artistMin = if (songs.size < 300) 3 else 6
        val genreMin = if (songs.size < 300) 5 else 12
        val decadeMin = if (songs.size < 300) 4 else 8

        val artists = artistStats(songs, plays, nowMs).filter { it.songs >= artistMin }
        val genres = genreStats(songs, plays).filter { it.songs >= genreMin }

        val recents = recentRequests.map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy(NlpText::normalize).take(MAX_RECENT).map(MixSuggestion::Recent)
        val taken = recents.mapTo(HashSet()) { NlpText.normalize(it.text) }

        // Recents, then the top artist always and a daily rotation of the next ones.
        val rankedArtists = rotate(artists.sortedByDescending { it.score }, keepFirst = 1, pool = 8, random)
            .filter { NlpText.normalize(it.name) !in taken }
        val favouriteArtists = rankedArtists.take(MAX_PER_ROW - recents.size)
        favouriteArtists.forEach { taken += NlpText.normalize(it.name) }
        val you = recents + favouriteArtists.map { MixSuggestion.Artist(it.name) }

        // Genres, and the strongest decade of one of them.
        val rankedGenres = rotate(genres.sortedByDescending { it.score }, keepFirst = 1, pool = 6, random)
            .filter { NlpText.normalize(it.name) !in taken }
        val decadeGenres = rankedGenres.mapNotNull { genre ->
            genre.decades.maxByOrNull { it.value }?.takeIf { it.value >= decadeMin }
                ?.let { MixSuggestion.DecadeGenre(it.key, genre.name) }
        }
        val library = buildList<MixSuggestion> {
            rankedGenres.take(if (decadeGenres.isNotEmpty()) MAX_PER_ROW - 1 else MAX_PER_ROW)
                .mapTo(this) { MixSuggestion.Genre(it.name) }
            decadeGenres.firstOrNull()?.let { add(1.coerceAtMost(size), it) }
        }

        // Moments for now, and one artist worth coming back to.
        val rediscover = artists
            .filter { it.name.let(NlpText::normalize) !in taken && it.songs >= artistMin * 2 }
            .filter { it.lastPlayedMs == 0L || nowMs - it.lastPlayedMs > REDISCOVER_AFTER_MS }
            .sortedByDescending { it.songs }
            .take(5).shuffled(random).firstOrNull()
        val moments = momentsFor(calendar)
        val now = buildList<MixSuggestion> {
            moments.take(if (rediscover != null) MAX_PER_ROW - 1 else MAX_PER_ROW).mapTo(this, MixSuggestion::Moment)
            rediscover?.let { add(1.coerceAtMost(size), MixSuggestion.Artist(it.name, rediscover = true)) }
        }

        // Next best candidates, used when the ones above are rejected or there are too few.
        val spare = interleave(
            rankedArtists.drop(favouriteArtists.size).map { MixSuggestion.Artist(it.name) },
            rankedGenres.drop(library.count { it is MixSuggestion.Genre }).map { MixSuggestion.Genre(it.name) },
            moments.drop(now.count { it is MixSuggestion.Moment }).map(MixSuggestion::Moment) +
                MixMoment.entries.filter { it !in moments }.map(MixSuggestion::Moment),
            decadeGenres.drop(1),
        )

        val chosen = LinkedHashMap<String, MixSuggestion>()
        val rejected = HashSet<String>()
        val shown = HashSet<String>()
        fun tryAdd(candidate: MixSuggestion) {
            if (chosen.size >= ROWS * MAX_PER_ROW || candidate.key in chosen || candidate.key in rejected) return
            val text = NlpText.normalize(textOf(candidate))
            if (text in shown) return
            if (canMix(candidate)) { chosen[candidate.key] = candidate; shown += text } else rejected += candidate.key
        }
        (you + library + now).forEach(::tryAdd)
        spare.forEach(::tryAdd)
        return distribute(chosen.values.toList())
    }

    private fun defaultText(suggestion: MixSuggestion): String = when (suggestion) {
        is MixSuggestion.Recent -> suggestion.text
        is MixSuggestion.Artist -> suggestion.name
        is MixSuggestion.Genre -> suggestion.name
        is MixSuggestion.DecadeGenre -> "${suggestion.decade} ${suggestion.genre}"
        is MixSuggestion.Moment -> suggestion.moment.name
    }

    /** Splits [items], in order, over up to [ROWS] rows whose sizes differ by at most one. */
    internal fun <T> distribute(items: List<T>): List<List<T>> {
        val rows = minOf(ROWS, items.size)
        if (rows == 0) return emptyList()
        val base = items.size / rows
        val extra = items.size % rows
        var start = 0
        return List(rows) { row ->
            val size = base + if (row < extra) 1 else 0
            items.subList(start, start + size).also { start += size }
        }
    }

    private fun <T> interleave(vararg lists: List<T>): List<T> = buildList {
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { list -> list.getOrNull(i)?.let(::add) }
    }

    /** Moments that fit the hour (and the weekend), most fitting first. */
    fun momentsFor(calendar: Calendar): List<MixMoment> {
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        val weekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY
        val weekendNight = day == Calendar.FRIDAY || day == Calendar.SATURDAY
        return when (hour) {
            in 5..10 -> listOf(MixMoment.MORNING, MixMoment.WORKOUT, if (weekend) MixMoment.FEEL_GOOD else MixMoment.STUDY)
            in 11..16 -> if (weekend) listOf(MixMoment.FEEL_GOOD, MixMoment.WORKOUT, MixMoment.CHILL_EVENING)
                else listOf(MixMoment.STUDY, MixMoment.FEEL_GOOD, MixMoment.WORKOUT)
            in 17..20 -> listOf(MixMoment.WORKOUT, MixMoment.CHILL_EVENING, if (weekendNight) MixMoment.PARTY else MixMoment.FEEL_GOOD)
            in 21..23 -> if (weekendNight) listOf(MixMoment.PARTY, MixMoment.CHILL_EVENING, MixMoment.LATE_NIGHT)
                else listOf(MixMoment.CHILL_EVENING, MixMoment.LATE_NIGHT, MixMoment.STUDY)
            else -> if (weekend) listOf(MixMoment.LATE_NIGHT, MixMoment.PARTY, MixMoment.CHILL_EVENING)
                else listOf(MixMoment.LATE_NIGHT, MixMoment.CHILL_EVENING, MixMoment.STUDY)
        }
    }

    /** Keeps the first [keepFirst] in place and shuffles the rest of the top [pool]. */
    private fun <T> rotate(ranked: List<T>, keepFirst: Int, pool: Int, random: Random): List<T> =
        ranked.take(keepFirst) + ranked.drop(keepFirst).take(pool - keepFirst).shuffled(random) + ranked.drop(pool)

    private class ArtistStat(val name: String, val songs: Int, val score: Double, val lastPlayedMs: Long)

    private class GenreStat(val name: String, val songs: Int, val score: Double, val decades: Map<Int, Int>)

    private fun artistStats(songs: List<Song>, plays: Map<String, MixSuggestionPlays>, nowMs: Long): List<ArtistStat> =
        songs.groupBy { song -> primaryArtist(song)?.let(NlpText::normalize) }
            .filterKeys { it != null }
            .map { (_, group) ->
                // The spelling most of the artist's songs use.
                val name = group.groupingBy { primaryArtist(it)!! }.eachCount().maxBy { it.value }.key
                val playCount = group.sumOf { plays[it.id]?.playCount ?: 0 }
                val lastPlayed = group.maxOf { plays[it.id]?.lastPlayedMs ?: 0L }
                val favourites = group.count { it.isFavorite }
                // Recent listening counts more than old habits; a big catalogue alone counts little.
                val recency = if (lastPlayed > 0) 1.0 / (1.0 + (nowMs - lastPlayed).coerceAtLeast(0) / 604_800_000.0) else 0.0
                ArtistStat(name, group.size, playCount + favourites * 3.0 + recency * 10 + group.size * 0.1, lastPlayed)
            }

    private fun genreStats(songs: List<Song>, plays: Map<String, MixSuggestionPlays>): List<GenreStat> {
        val byGenre = HashMap<String, MutableList<Song>>()
        val spelling = HashMap<String, MutableMap<String, Int>>()
        songs.forEach { song ->
            genresOf(song.genre).forEach { genre ->
                val key = NlpText.normalize(genre)
                byGenre.getOrPut(key) { mutableListOf() }.add(song)
                spelling.getOrPut(key) { HashMap() }.merge(genre, 1, Int::plus)
            }
        }
        return byGenre.map { (key, group) ->
            val name = spelling.getValue(key).maxBy { it.value }.key
            val playCount = group.sumOf { plays[it.id]?.playCount ?: 0 }
            val decades = group.filter { it.year in 1950..2099 }.groupingBy { it.year / 10 * 10 }.eachCount()
            GenreStat(name, group.size, group.size + playCount * 0.5, decades)
        }
    }

    /**
     * The song's primary artist, or null if unknown. Multi-artist credits are split at sync
     * time, so this never splits names itself ("Earth, Wind & Fire" stays whole).
     */
    internal fun primaryArtist(song: Song): String? {
        val name = (song.artists.firstOrNull { it.isPrimary }?.name ?: song.artist).trim()
        return name.takeUnless { it.lowercase() in unknownValues || it.length > 40 }
    }

    /** Genre tags of a song, split on the usual separators, in their display spelling. */
    internal fun genresOf(tag: String?): List<String> =
        tag.orEmpty().split(';', ',', '/', '|')
            .map { part -> part.trim().replaceFirstChar { it.titlecase() } }
            .filter { it.lowercase() !in unknownValues && it.length in 2..30 && it.any(Char::isLetter) }
            .distinctBy(NlpText::normalize)
}
