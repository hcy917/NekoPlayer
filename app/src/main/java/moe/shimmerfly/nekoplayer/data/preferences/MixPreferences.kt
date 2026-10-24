package moe.shimmerfly.nekoplayer.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.smartMixDataStore: DataStore<Preferences> by preferencesDataStore(name = "smart_mix")

/**
 * Smart Mix memory: requests that produced a mix (for the composer's suggestions), the songs of
 * the last few mixes (so a new mix does not repeat them), and which saved playlists came from
 * Smart Mix (generated playlists are exposure, never evidence of taste).
 *
 * Kept in its own store, not the shared settings store, because it is device-local listening
 * history: song ids from a backup would not match another library.
 */
@Singleton
class MixPreferences internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.smartMixDataStore)

    private object Keys {
        val MIX_IDS = (0 until GENERATIONS).map { stringSetPreferencesKey("offline_mix_ids_v1_$it") }
        val MIX_TIMES = (0 until GENERATIONS).map { longPreferencesKey("offline_mix_time_v1_$it") }
        /** JSON array, newest first. */
        val RECENT_REQUESTS = stringPreferencesKey("smart_mix_recent_requests_v1")
        val SAVED_PLAYLIST_IDS = stringSetPreferencesKey("smart_mix_saved_playlist_ids_v1")
    }

    /** Songs of the last [GENERATIONS] mixes made within [FRESHNESS_WINDOW_MS]. Missing keys are neutral. */
    suspend fun recentSmartMixIds(nowMs: Long): Set<String> {
        val prefs = dataStore.data.first()
        return buildSet {
            for (i in Keys.MIX_IDS.indices) {
                val time = prefs[Keys.MIX_TIMES[i]] ?: continue
                if (time > 0 && time <= nowMs && nowMs - time < FRESHNESS_WINDOW_MS) {
                    addAll(prefs[Keys.MIX_IDS[i]].orEmpty().take(MAX_IDS_PER_MIX))
                }
            }
        }
    }

    suspend fun recordSmartMix(ids: List<String>, nowMs: Long) {
        if (ids.isEmpty()) return
        dataStore.edit { prefs ->
            for (i in GENERATIONS - 1 downTo 1) {
                prefs[Keys.MIX_IDS[i]] = prefs[Keys.MIX_IDS[i - 1]].orEmpty()
                prefs[Keys.MIX_TIMES[i]] = prefs[Keys.MIX_TIMES[i - 1]] ?: 0L
            }
            prefs[Keys.MIX_IDS[0]] = ids.take(MAX_IDS_PER_MIX).toSet()
            prefs[Keys.MIX_TIMES[0]] = nowMs
        }
    }

    /** Requests that produced a mix, newest first, for the composer's suggestions. */
    suspend fun recentMixRequests(): List<String> =
        decodeRequests(dataStore.data.first()[Keys.RECENT_REQUESTS])

    suspend fun recordMixRequest(request: String) {
        val text = request.replace(WHITESPACE, " ").trim().take(MAX_REQUEST_LENGTH)
        if (text.isEmpty()) return
        dataStore.edit { prefs ->
            val kept = decodeRequests(prefs[Keys.RECENT_REQUESTS]).filterNot { it.equals(text, ignoreCase = true) }
            prefs[Keys.RECENT_REQUESTS] = Json.encodeToString((listOf(text) + kept).take(MAX_RECENT_REQUESTS))
        }
    }

    /** Ids of playlists saved from a Smart Mix preview. */
    suspend fun smartMixPlaylistIds(): Set<String> =
        dataStore.data.first()[Keys.SAVED_PLAYLIST_IDS].orEmpty()

    suspend fun markSmartMixPlaylist(playlistId: String) {
        dataStore.edit { prefs ->
            prefs[Keys.SAVED_PLAYLIST_IDS] = prefs[Keys.SAVED_PLAYLIST_IDS].orEmpty() + playlistId
        }
    }

    /** Forgets recent requests and the freshness memory that keeps new mixes from repeating old ones. */
    suspend fun clearSmartMixHistory() {
        dataStore.edit { prefs ->
            Keys.MIX_IDS.forEach { prefs.remove(it) }
            Keys.MIX_TIMES.forEach { prefs.remove(it) }
            prefs.remove(Keys.RECENT_REQUESTS)
        }
    }

    private fun decodeRequests(raw: String?): List<String> =
        raw?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()

    private companion object {
        const val GENERATIONS = 3
        const val MAX_IDS_PER_MIX = 200
        const val FRESHNESS_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_RECENT_REQUESTS = 6
        const val MAX_REQUEST_LENGTH = 80
        val WHITESPACE = Regex("\\s+")
    }
}
