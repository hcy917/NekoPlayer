package moe.shimmerfly.nekoplayer.presentation.screens

import moe.shimmerfly.nekoplayer.data.model.LibraryTabId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibraryTabResolutionTest {
    private val tabs = listOf("SONGS", "ALBUMS", "ARTIST", "PLAYLISTS", "FOLDERS", "LIKED")

    @Test
    fun `the pager index resolves to the tab stored at that position`() {
        assertEquals(LibraryTabId.SONGS, resolveVisibleLibraryTabId(tabs, 0, fallback = LibraryTabId.LIKED))
        assertEquals(LibraryTabId.ARTISTS, resolveVisibleLibraryTabId(tabs, 2, fallback = LibraryTabId.SONGS))
        assertEquals(LibraryTabId.LIKED, resolveVisibleLibraryTabId(tabs, 5, fallback = LibraryTabId.SONGS))
    }

    @Test
    fun `the visible tab wins over a view model tab that has not caught up yet`() {
        assertEquals(LibraryTabId.PLAYLISTS, resolveVisibleLibraryTabId(tabs, 3, fallback = LibraryTabId.SONGS))
    }

    @Test
    fun `an index outside the tab list falls back to the view model tab`() {
        assertEquals(LibraryTabId.FOLDERS, resolveVisibleLibraryTabId(tabs, 6, fallback = LibraryTabId.FOLDERS))
        assertEquals(LibraryTabId.ALBUMS, resolveVisibleLibraryTabId(tabs, -1, fallback = LibraryTabId.ALBUMS))
        assertEquals(LibraryTabId.LIKED, resolveVisibleLibraryTabId(emptyList(), 0, fallback = LibraryTabId.LIKED))
    }

    @Test
    fun `an unknown stored tab identifier falls back to the view model tab`() {
        val tabsWithUnknown = listOf("SONGS", "PODCASTS", "LIKED")

        assertEquals(LibraryTabId.ALBUMS, resolveVisibleLibraryTabId(tabsWithUnknown, 1, fallback = LibraryTabId.ALBUMS))
    }
}
