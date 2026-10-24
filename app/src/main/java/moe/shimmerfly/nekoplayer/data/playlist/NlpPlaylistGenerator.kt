package moe.shimmerfly.nekoplayer.data.playlist

import moe.shimmerfly.nekoplayer.data.DailyMixManager
import moe.shimmerfly.nekoplayer.data.media.TagBpmReader
import moe.shimmerfly.nekoplayer.data.model.Song
import moe.shimmerfly.nekoplayer.data.playlist.nlp.ConceptTable
import moe.shimmerfly.nekoplayer.data.playlist.nlp.LibraryIndex
import moe.shimmerfly.nekoplayer.data.playlist.nlp.MixContext
import moe.shimmerfly.nekoplayer.data.playlist.nlp.NlpLexicon
import moe.shimmerfly.nekoplayer.data.playlist.nlp.NlpText
import moe.shimmerfly.nekoplayer.data.playlist.nlp.PlaylistIntentEngine
import moe.shimmerfly.nekoplayer.data.preferences.MixPreferences
import moe.shimmerfly.nekoplayer.data.preferences.PlaylistPreferencesRepository
import moe.shimmerfly.nekoplayer.data.repository.MusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Turns a natural-language description ("songs to lift weights to") into a ranked song list,
 * fully offline: it feeds the local library through [PlaylistIntentEngine]'s TF-IDF +
 * stemmed-synonym scorer and the personalized ranker. No network, no API keys.
 *
 * This is the thin orchestration layer around the pure `nlp` package: it loads the songs,
 * caches the inverted-frequency [LibraryIndex] across requests (rebuilt incrementally when the
 * library signature changes, with artist genres from the bundled table), supplies play-count
 * engagement stats, playlist relationships and the freshness memory of recent mixes, and — only
 * when the query actually expresses a tempo/energy preference — reads tag-embedded BPM from local
 * files so tempo matching can use real numbers. There is no on-device audio analysis: songs
 * without a tagged BPM simply fall back to the engine's genre-derived energy heuristic.
 */
@Singleton
class NlpPlaylistGenerator @Inject constructor(
    private val musicRepository: MusicRepository,
    private val dailyMixManager: DailyMixManager,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    private val mixPreferences: MixPreferences,
    private val conceptTableLoader: ConceptTableLoader,
    private val artistGenreLoader: ArtistGenreLoader,
) {

    private val indexMutex = Mutex()
    @Volatile private var cachedIndex: LibraryIndex? = null
    private val generationMutex = Mutex()

    /** Mixes made in this process; persisted ones come from [MixPreferences]. */
    private val recentMixes = ArrayDeque<Pair<Long, List<String>>>()

    // Tag BPM reads cost file I/O, so the whole-library sweep is memoized per library signature.
    private val bpmMutex = Mutex()
    @Volatile private var cachedBpm: Pair<Long, Map<String, Float>>? = null

    /**
     * Generates a mix for [description], returning the matched songs in ranked order, and records
     * it in the freshness memory so the next mix avoids repeating it. Heavy work (indexing,
     * scoring, tag reads) runs off the main thread.
     */
    suspend fun generate(
        description: String,
        minLength: Int = DEFAULT_MIN_LENGTH,
        maxLength: Int = DEFAULT_MAX_LENGTH,
    ): List<Song> = withContext(Dispatchers.Default) {
        generationMutex.withLock {
            val songs = musicRepository.getAllSongsOnce()
            if (songs.isEmpty()) return@withLock emptyList()

            val index = getOrBuildIndex(songs)
            val engagements = loadEngagements()
            val concepts = conceptTableLoader.load()

            val parsed = PlaylistIntentEngine.parse(description, index, concepts)
            // Tag BPM is only worth the file reads when the query cares about tempo/energy at all.
            val bpmBySongId = if (parsed.hasEnergySignal) readTagBpms(songs, fileSignatureOf(songs)) else emptyMap()

            val now = System.currentTimeMillis()
            val mixContext = buildMixContext(parsed.normalizedQuery, now)
            val resultIds = PlaylistIntentEngine.generate(
                query = description,
                minLength = minLength,
                maxLength = maxLength,
                index = index,
                engagements = engagements,
                bpmBySongId = bpmBySongId,
                context = mixContext,
                concepts = concepts,
            )
            Timber.tag(TAG).d("NLP playlist: len=%d-%d -> %d songs", minLength, maxLength, resultIds.size)

            if (resultIds.isNotEmpty()) {
                recentMixes.addLast(now to resultIds)
                while (recentMixes.size > MAX_SESSION_MIXES) recentMixes.removeFirst()
                try {
                    mixPreferences.recordSmartMix(resultIds, now)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).w(e, "Unable to persist mix freshness")
                }
            }

            val songsById = songs.associateBy { it.id }
            resultIds.mapNotNull { songsById[it] }
        }
    }

    /** Forgets the freshness memory and recent requests, persisted and in this process. */
    suspend fun clearHistory() {
        generationMutex.withLock { recentMixes.clear() }
        mixPreferences.clearSmartMixHistory()
    }

    /**
     * A side-effect-free check of which requests would produce a mix, for the composer's
     * suggestions. It records nothing and reads no tag BPM: tempo only adds information, so a
     * request that matches without it still matches with it.
     */
    suspend fun createMixProbe(songs: List<Song>): MixProbe = withContext(Dispatchers.Default) {
        val index = if (songs.isEmpty()) null else getOrBuildIndex(songs)
        MixProbe(index, loadEngagements(), conceptTableLoader.load())
    }

    class MixProbe internal constructor(
        private val index: LibraryIndex?,
        private val engagements: Map<String, DailyMixManager.SongEngagementStats>,
        private val concepts: ConceptTable,
    ) {
        fun canMix(query: String): Boolean = index != null && index.songs.isNotEmpty() &&
            PlaylistIntentEngine.generate(
                query, PROBE_LENGTH, PROBE_LENGTH, index, engagements, Random(PROBE_SEED), concepts = concepts,
            ).isNotEmpty()

        private companion object {
            const val PROBE_LENGTH = 10
            const val PROBE_SEED = 1
        }
    }

    /**
     * Playlist relationships and exposure: a "like <playlist>" request seeds from that playlist,
     * user playlists are taste evidence, and Smart Mix playlists plus recent mixes are exposure
     * the ranker steers away from.
     */
    private suspend fun buildMixContext(normalizedQuery: String, now: Long): MixContext {
        val playlists = try {
            playlistPreferencesRepository.getPlaylistsOnce()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Unable to load local playlist relationships")
            emptyList()
        }
        val generatedPlaylistIds = try {
            mixPreferences.smartMixPlaylistIds()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptySet()
        }
        val storedExposure = try {
            mixPreferences.recentSmartMixIds(now)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptySet()
        }
        val playlistSeed = playlists.firstOrNull { playlist ->
            val name = NlpText.normalize(playlist.name).trim()
            name.isNotEmpty() && NlpLexicon.similarityPhrases.any { cue -> normalizedQuery == "$cue $name" }
        }?.songIds?.toSet().orEmpty()
        val generatedExposure = playlists.asSequence()
            .filter { it.id in generatedPlaylistIds && now - it.lastModified in 0..FRESHNESS_WINDOW_MS }
            .sortedByDescending { it.lastModified }
            .take(MAX_SESSION_MIXES)
            .flatMap { it.songIds.asSequence().take(MAX_EXPOSURE_PER_PLAYLIST) }
            .toSet()
        while (recentMixes.firstOrNull()?.let { now - it.first !in 0..FRESHNESS_WINDOW_MS } == true) {
            recentMixes.removeFirst()
        }
        return MixContext(
            seedIds = playlistSeed,
            playlists = playlists
                .filter { it.id !in generatedPlaylistIds && !it.isQueueGenerated }
                .map { it.songIds.toSet() },
            recentMixIds = recentMixes.flatMap { it.second }.toSet() + generatedExposure + storedExposure,
            nowMs = now,
        )
    }

    private suspend fun loadEngagements(): Map<String, DailyMixManager.SongEngagementStats> = try {
        dailyMixManager.getAllEngagementStats()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Timber.tag(TAG).e(e, "Error loading engagement stats")
        emptyMap()
    }

    /**
     * Cached inverted-frequency index, rebuilt only when the library signature changes. A rebuild
     * reuses the tokenization of unchanged songs and gives untagged songs a genre from their
     * artist via the bundled artist table.
     */
    private suspend fun getOrBuildIndex(songs: List<Song>): LibraryIndex {
        val signature = LibraryIndex.signatureOf(songs)
        cachedIndex?.let { if (it.signature == signature) return it }

        return indexMutex.withLock {
            cachedIndex?.let { if (it.signature == signature) return@withLock it }
            val artistGenres = withContext(Dispatchers.IO) {
                artistGenreLoader.load(LibraryIndex.artistKeysOf(songs))
            }
            LibraryIndex.build(songs, cachedIndex, artistGenres).also { cachedIndex = it }
        }
    }

    /**
     * Identity of the files behind [songs]: changes when a song is added, removed, moved or
     * rewritten, but not on a favorite or other in-app metadata change, so those do not re-read
     * every tag.
     */
    private fun fileSignatureOf(songs: List<Song>): Long {
        var hash = songs.size.toLong()
        for (song in songs) {
            hash = 31 * hash + song.id.hashCode()
            hash = 31 * hash + song.path.hashCode()
            hash = 31 * hash + song.dateModified
        }
        return hash
    }

    /**
     * Reads the tag-embedded BPM of every local song, with bounded parallelism, memoized per
     * file [signature]. Cloud-only songs (no local path) and files without a plausible
     * tagged value are simply absent from the map.
     */
    private suspend fun readTagBpms(songs: List<Song>, signature: Long): Map<String, Float> {
        cachedBpm?.let { (cachedSignature, map) -> if (cachedSignature == signature) return map }

        return bpmMutex.withLock {
            cachedBpm?.let { (cachedSignature, map) -> if (cachedSignature == signature) return@withLock map }

            val bpmBySongId = withContext(Dispatchers.IO) {
                val permits = Semaphore(BPM_READ_PARALLELISM)
                coroutineScope {
                    songs.mapNotNull { song ->
                        val path = song.path.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        async {
                            permits.withPermit {
                                val bpm = try {
                                    TagBpmReader.readBpm(path)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    Timber.tag(TAG).d(e, "Tag BPM read failed for %s", path)
                                    null
                                }?.takeIf { it in PLAUSIBLE_TAG_BPM_RANGE }
                                if (bpm != null) song.id to bpm else null
                            }
                        }
                    }.awaitAll().filterNotNull().toMap()
                }
            }

            cachedBpm = signature to bpmBySongId
            bpmBySongId
        }
    }

    companion object {
        private const val TAG = "NlpPlaylistGenerator"
        const val DEFAULT_MIN_LENGTH = 10
        const val DEFAULT_MAX_LENGTH = 20
        private const val BPM_READ_PARALLELISM = 8
        private const val MAX_SESSION_MIXES = 3
        private const val MAX_EXPOSURE_PER_PLAYLIST = 200
        private const val FRESHNESS_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

        // Mirrors TrackBpmRepository: implausible tag values (mis-tagged files, unit
        // confusion) are treated as absent rather than poisoning tempo matching.
        private val PLAUSIBLE_TAG_BPM_RANGE = 40f..220f
    }
}
