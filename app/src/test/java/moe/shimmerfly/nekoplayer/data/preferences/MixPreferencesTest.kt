package moe.shimmerfly.nekoplayer.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MixPreferencesTest {
    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test
    fun `upgrade defaults and bounded history survive repository recreation`() = runBlocking {
        val store = MemoryStore()
        val prefs = MixPreferences(store)
        assertTrue(prefs.recentSmartMixIds(1000).isEmpty())
        for (i in 1..4) prefs.recordSmartMix(listOf("$i"), i * 100L)
        assertEquals(setOf("2", "3", "4"), MixPreferences(store).recentSmartMixIds(1000))
        assertTrue(prefs.recentSmartMixIds(604_801_000).isEmpty())
        assertTrue(prefs.recentSmartMixIds(1).isEmpty())
        prefs.recordSmartMix((0..500).map(Int::toString), 1000)
        assertTrue(prefs.recentSmartMixIds(1000).size <= 202)
    }

    @Test
    fun `clearing history forgets requests and freshness but keeps saved playlist markers`() = runBlocking {
        val prefs = MixPreferences(MemoryStore())
        prefs.recordSmartMix(listOf("a"), 100)
        prefs.recordMixRequest("  90s   rock ")
        prefs.recordMixRequest("Workout")
        prefs.recordMixRequest("90S ROCK")
        prefs.markSmartMixPlaylist("playlist-1")
        assertEquals(listOf("90S ROCK", "Workout"), prefs.recentMixRequests())

        prefs.clearSmartMixHistory()

        assertTrue(prefs.recentSmartMixIds(200).isEmpty())
        assertTrue(prefs.recentMixRequests().isEmpty())
        assertEquals(setOf("playlist-1"), prefs.smartMixPlaylistIds())
    }
}
