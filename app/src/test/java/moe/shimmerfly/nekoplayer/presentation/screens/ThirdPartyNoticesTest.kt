package moe.shimmerfly.nekoplayer.presentation.screens

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThirdPartyNoticesTest {

    private val sample = """
        # Third-Party Notices

        NekoPlayer is licensed under the GPL-3.0.

        Bundled fonts:

        | Asset | Source | License |
        | --- | --- | --- |
        | Roboto Flex (`genre_variable.ttf`) | `https://github.com/googlefonts/roboto-flex` | SIL Open Font License 1.1 |

        Native runtime artifacts:

        | Artifact | Native library | License/source evidence |
        | --- | --- | --- |
        | `io.github.kyant0:taglib:1.0.6` | `libtaglib.so` | Apache-2.0, `https://github.com/Kyant0/taglib` |

        ## Bundled Data Assets

        | Asset | Source | License |
        | --- | --- | --- |
        | Artist genres | Wikidata | CC0 1.0 |
    """.trimIndent()

    @Test
    fun `each table row becomes a notice and header rows are skipped`() {
        val notices = parseThirdPartyNotices(sample)

        assertEquals(
            listOf("Roboto Flex (genre_variable.ttf)", "io.github.kyant0:taglib:1.0.6", "Artist genres"),
            notices.map { it.name },
        )
        assertEquals(notices.size, notices.map { it.id }.toSet().size)
    }

    @Test
    fun `sections from colon lines and headings decide the category`() {
        val notices = parseThirdPartyNotices(sample).associateBy { it.name }

        assertEquals(LicenseCategory.FONTS, notices.getValue("Roboto Flex (genre_variable.ttf)").category)
        assertEquals("Native runtime artifacts", notices.getValue("io.github.kyant0:taglib:1.0.6").section)
        assertEquals(LicenseCategory.LIBRARIES, notices.getValue("io.github.kyant0:taglib:1.0.6").category)
        assertEquals(LicenseCategory.DATA, notices.getValue("Artist genres").category)
    }

    @Test
    fun `license evidence after a comma moves to the source`() {
        val taglib = parseThirdPartyNotices(sample).single { it.name.startsWith("io.github.kyant0") }

        assertEquals("Apache-2.0", taglib.licenseName)
        assertEquals("libtaglib.so · https://github.com/Kyant0/taglib", taglib.source)
    }

    @Test
    fun `search matches name, source and license`() {
        val font = parseThirdPartyNotices(sample).first()

        assertTrue(font.matchesQuery("roboto"))
        assertTrue(font.matchesQuery("googlefonts"))
        assertTrue(font.matchesQuery("open font"))
        assertTrue(font.matchesQuery("  "))
        assertFalse(font.matchesQuery("apache"))
        assertTrue(font.usesOpenFontLicense())
    }

    @Test
    fun `bundled notices asset parses into fully attributed rows`() {
        val asset = File("src/main/assets/licenses/THIRD_PARTY_NOTICES.md")
        val notices = parseThirdPartyNotices(asset.readText())

        assertTrue(notices.isNotEmpty())
        assertTrue(notices.any { it.category == LicenseCategory.FONTS })
        notices.forEach { notice ->
            assertTrue(notice.name.isNotBlank(), "name of $notice")
            assertTrue(notice.licenseName.isNotBlank(), "license of $notice")
            assertTrue(notice.section.isNotBlank(), "section of $notice")
        }
    }
}
