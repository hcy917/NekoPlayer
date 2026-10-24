package moe.shimmerfly.nekoplayer.presentation.screens

import androidx.compose.runtime.Immutable

enum class LicenseCategory { LIBRARIES, FONTS, DATA }

@Immutable
data class LicenseNotice(
    val id: String,
    val name: String,
    val source: String,
    val licenseName: String,
    val section: String,
    val category: LicenseCategory,
)

private val TableSeparatorCell = Regex("^:?-{3,}:?$")

/**
 * Parses the bundled `licenses/THIRD_PARTY_NOTICES.md` into one [LicenseNotice] per table row.
 *
 * Sections start at a `##` heading or a short line ending in `:`. In each table the first row is
 * the header, the first column is the component, the last column is its license, and the columns
 * in between are its source. License cells may append source evidence after `, `, which moves
 * to [LicenseNotice.source].
 */
internal fun parseThirdPartyNotices(markdown: String): List<LicenseNotice> {
    val notices = mutableListOf<LicenseNotice>()
    var section = ""
    var inTable = false

    for (rawLine in markdown.lineSequence()) {
        val line = rawLine.trim()
        if (!line.startsWith("|")) {
            inTable = false
            when {
                line.startsWith("##") -> section = line.trimStart('#').trim()
                line.endsWith(":") && !line.startsWith("#") && line.length <= 60 ->
                    section = line.removeSuffix(":").trim()
            }
            continue
        }

        val cells = line.trim('|').split('|').map { it.replace("`", "").trim() }
        if (cells.all { TableSeparatorCell.matches(it) }) continue
        if (!inTable) {
            inTable = true
            continue
        }
        if (cells.size < 2 || cells.first().isBlank()) continue

        val licenseCell = cells.last()
        val licenseName = licenseCell.substringBefore(", ").trim()
        val licenseEvidence = licenseCell.substringAfter(", ", "").trim()
        val source = (cells.subList(1, cells.lastIndex) + licenseEvidence)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

        notices += LicenseNotice(
            id = "notice_${notices.size}",
            name = cells.first(),
            source = source,
            licenseName = licenseName,
            section = section,
            category = categoryForSection(section),
        )
    }
    return notices
}

private fun categoryForSection(section: String): LicenseCategory {
    val normalized = section.lowercase()
    return when {
        "font" in normalized -> LicenseCategory.FONTS
        "data" in normalized -> LicenseCategory.DATA
        else -> LicenseCategory.LIBRARIES
    }
}

internal fun LicenseNotice.matchesQuery(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim()
    return name.contains(needle, ignoreCase = true) ||
        source.contains(needle, ignoreCase = true) ||
        licenseName.contains(needle, ignoreCase = true)
}

internal fun LicenseNotice.usesOpenFontLicense(): Boolean =
    licenseName.contains("Open Font License", ignoreCase = true) ||
        licenseName.contains("OFL", ignoreCase = true)
