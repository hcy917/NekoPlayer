package moe.shimmerfly.nekoplayer.data.sourcemarket

import kotlinx.serialization.Serializable

/**
 * Minimal GitHub release models for the source market.
 *
 * The downstream app keeps these in `data/github` next to its in-app updater and fills
 * [GitHubToken] from a build-time obfuscated secret. NekoPlayer has no such secret, so the
 * token holder is a plain empty stub and every call runs anonymously.
 */
@Serializable
data class GitHubReleaseAsset(
    val name: String = "",
    val size: Long = 0,
    val browser_download_url: String = "",
)

@Serializable
data class GitHubRelease(
    val tag_name: String = "",
    val body: String = "",
    val published_at: String = "",
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

/** Optional PAT for GitHub API calls; empty means anonymous (rate-limited but works). */
internal object GitHubToken {
    val value: String = ""
}
