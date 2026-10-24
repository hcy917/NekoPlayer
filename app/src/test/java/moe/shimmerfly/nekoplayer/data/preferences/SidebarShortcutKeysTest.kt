package moe.shimmerfly.nekoplayer.data.preferences

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SidebarShortcutKeysTest {

    @Test
    fun `stats shortcut is available and enabled by default`() {
        assertThat(SidebarShortcutKeys.ALL).contains(SidebarShortcutKeys.STATS)
        assertThat(SidebarShortcutKeys.DEFAULT).contains(SidebarShortcutKeys.STATS)
    }

    @Test
    fun `stats shortcut key matches the stats route`() {
        assertThat(SidebarShortcutKeys.STATS).isEqualTo("stats")
    }

    @Test
    fun `every shortcut is listed once and enabled by default`() {
        assertThat(SidebarShortcutKeys.ALL).containsNoDuplicates()
        assertThat(SidebarShortcutKeys.DEFAULT).containsExactlyElementsIn(SidebarShortcutKeys.ALL)
    }
}
