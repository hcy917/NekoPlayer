package moe.shimmerfly.nekoplayer.presentation.screens

import androidx.compose.foundation.lazy.LazyListItemInfo
import moe.shimmerfly.nekoplayer.data.model.MusicFolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryFolderLocateScopeTest {
    private val rootFolders = listOf(MusicFolder(path = "/storage/emulated/0/Music", name = "Music"))

    @Test
    fun `locate includes the current folder and its descendants`() {
        assertTrue(isSongParentInFolderScope("/storage/emulated/0/Music", "/storage/emulated/0/Music", rootFolders))
        assertTrue(isSongParentInFolderScope("/storage/emulated/0/Music/Rock/Live", "/storage/emulated/0/Music/", rootFolders))
    }

    @Test
    fun `locate stays hidden in unrelated folders and when moving below the song`() {
        assertFalse(isSongParentInFolderScope("/storage/emulated/0/Music/Rock", "/storage/emulated/0/Music/Jazz", rootFolders))
        assertFalse(isSongParentInFolderScope("/storage/emulated/0/Music/Rock", "/storage/emulated/0/Music/Jazz/Live", rootFolders))
        assertFalse(isSongParentInFolderScope("/storage/emulated/0/Music", "/storage/emulated/0/Music/Rock", rootFolders))
    }

    @Test
    fun `similar folder prefixes are not descendants`() {
        assertFalse(isSongParentInFolderScope("/storage/emulated/0/Music2/Rock", "/storage/emulated/0/Music", rootFolders))
    }

    @Test
    fun `root locate is limited to displayed folder trees`() {
        assertTrue(isSongParentInFolderScope("/storage/emulated/0/Music/Rock", null, rootFolders))
        assertFalse(isSongParentInFolderScope("/storage/emulated/0/Downloads", null, rootFolders))
        assertFalse(isSongParentInFolderScope("/storage/emulated/0/Music/Rock", null, emptyList()))
        assertFalse(isSongParentInFolderScope(null, null, rootFolders))
    }

    @Test
    fun `the destination being navigated to shows the current folder`() {
        val rock = MusicFolder(path = "/storage/emulated/0/Music/Rock", name = "Rock")

        assertSame(rock, resolveFolderDestination(rock.path, currentFolder = rock, rememberedFolder = null))
    }

    @Test
    fun `an outgoing destination keeps its own folder after the current folder changes`() {
        val music = MusicFolder(path = "/storage/emulated/0/Music", name = "Music")
        val rock = MusicFolder(path = "/storage/emulated/0/Music/Rock", name = "Rock")

        assertSame(rock, resolveFolderDestination(rock.path, currentFolder = music, rememberedFolder = rock))
        assertSame(rock, resolveFolderDestination(rock.path, currentFolder = null, rememberedFolder = rock))
    }

    @Test
    fun `the root destination shows no folder`() {
        val rock = MusicFolder(path = "/storage/emulated/0/Music/Rock", name = "Rock")

        assertNull(resolveFolderDestination(null, currentFolder = rock, rememberedFolder = rock))
    }

    @Test
    fun `locate visibility is unknown until the list has been laid out`() {
        assertNull(isLocateTargetVisible(emptyList(), index = 3))
    }

    @Test
    fun `locate visibility matches the exact index of a laid out item`() {
        val visibleItems = visibleItems(4, 5, 6)

        assertEquals(true, isLocateTargetVisible(visibleItems, index = 5))
        assertEquals(false, isLocateTargetVisible(visibleItems, index = 3))
        assertEquals(false, isLocateTargetVisible(visibleItems, index = 7))
    }

    private fun visibleItems(vararg indices: Int): List<LazyListItemInfo> = indices.map { itemIndex ->
        object : LazyListItemInfo {
            override val index = itemIndex
            override val key: Any = itemIndex
            override val offset = 0
            override val size = 1
        }
    }
}
