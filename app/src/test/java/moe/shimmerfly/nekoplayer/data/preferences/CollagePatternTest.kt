package moe.shimmerfly.nekoplayer.data.preferences

import moe.shimmerfly.nekoplayer.R
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CollagePatternTest {

    @Test
    fun `every pattern round trips through its storage key`() {
        CollagePattern.entries.forEach { pattern ->
            assertEquals(pattern, CollagePattern.fromStorageKey(pattern.storageKey))
        }
    }

    @Test
    fun `storage keys are unique`() {
        val keys = CollagePattern.entries.map { it.storageKey }

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `material shape patterns keep the storage keys shared with other editions`() {
        assertEquals(CollagePattern.SOFT_ORBIT, CollagePattern.fromStorageKey("soft_orbit"))
        assertEquals(CollagePattern.ARROW_FANFARE, CollagePattern.fromStorageKey("arrow_fanfare"))
        assertEquals(CollagePattern.SUNNY_BLOOM, CollagePattern.fromStorageKey("sunny_bloom"))
        assertEquals(CollagePattern.PIXEL_COOKIE, CollagePattern.fromStorageKey("pixel_cookie"))
        assertEquals(CollagePattern.HEART_GARDEN, CollagePattern.fromStorageKey("heart_garden"))
    }

    @Test
    fun `unknown or missing key falls back to cosmic swirl`() {
        assertEquals(CollagePattern.COSMIC_SWIRL, CollagePattern.fromStorageKey("not_a_pattern"))
        assertEquals(CollagePattern.COSMIC_SWIRL, CollagePattern.fromStorageKey(null))
        assertEquals(CollagePattern.COSMIC_SWIRL, CollagePattern.default)
    }

    @Test
    fun `every pattern has its own label resource`() {
        CollagePattern.entries.forEach { pattern ->
            assertNotEquals(0, pattern.labelRes, "${pattern.name} has no label resource")
        }
        val labels = CollagePattern.entries.map { it.labelRes }

        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `every pattern label is named after its storage key`() {
        CollagePattern.entries.forEach { pattern ->
            assertEquals("collage_pattern_${pattern.storageKey}", stringResourceName(pattern.labelRes))
        }
    }

    @Test
    fun `every pattern label is translated in every locale`() {
        val resDir = resDirectory()
        val localeDirs = resDir.listFiles { file ->
            file.isDirectory && (file.name == "values" || LOCALE_DIR.matches(file.name))
        }.orEmpty()
        assertTrue(localeDirs.size > 1, "No locale folders found under $resDir")

        localeDirs.forEach { dir ->
            val xml = dir.listFiles { file -> file.extension == "xml" }.orEmpty()
                .joinToString("\n") { it.readText() }
            CollagePattern.entries.forEach { pattern ->
                val name = stringResourceName(pattern.labelRes)
                val value = Regex("""<string name="$name"[^>]*>([^<]*)</string>""")
                    .find(xml)?.groupValues?.get(1)?.trim()
                assertTrue(!value.isNullOrEmpty(), "${dir.name} has no text for $name")
            }
        }
    }

    private fun stringResourceName(id: Int): String =
        R.string::class.java.fields.first { it.getInt(null) == id }.name

    private fun resDirectory(): File = sequenceOf(
        File("src/main/res"),
        File("app/src/main/res"),
    ).first { it.isDirectory }

    private companion object {
        // Translation folders such as values-ru or values-zh-rCN, not qualifiers like values-night.
        val LOCALE_DIR = Regex("""values-[a-z]{2,3}(-r[A-Z]{2})?""")
    }
}
