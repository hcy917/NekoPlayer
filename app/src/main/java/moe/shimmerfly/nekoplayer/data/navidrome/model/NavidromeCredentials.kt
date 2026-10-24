package moe.shimmerfly.nekoplayer.data.navidrome.model

import moe.shimmerfly.nekoplayer.data.stream.CloudStreamSecurity
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Represents authentication credentials for a Navidrome/Subsonic server.
 *
 * Subsonic servers accept two authentication methods:
 * 1. Token-based authentication (t=xxx&s=xxx) - preferred, and what we try first
 * 2. Password in URL (p=enc:xxx) - used only when the server rejects tokens
 *
 * Which one is in play is tracked by the API client, not here; see
 * [moe.shimmerfly.nekoplayer.data.navidrome.model.NavidromeAuthMethod].
 *
 * @property serverUrl The base URL of the Navidrome server (e.g., "https://music.example.com")
 * @property username The username for authentication
 * @property password The password (stored securely, used to generate tokens)
 * @property clientId The client identifier sent to the server (default: "NekoPlayer")
 */
data class NavidromeCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
    val clientId: String = "NekoPlayer"
) {
    companion object {
        /**
         * The Subsonic API version supported by this implementation.
         */
        const val API_VERSION = "1.16.1"

        /**
         * Creates an empty credentials object.
         */
        fun empty() = NavidromeCredentials(
            serverUrl = "",
            username = "",
            password = "",
            clientId = "NekoPlayer"
        )
    }

    /**
     * Returns true if the credentials have all required fields populated.
     */
    val isValid: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /**
     * Returns the parsed and normalized server URL, or null if it is invalid.
     *
     * A URL typed without a scheme gets `http://` when its host is a local network or VPN
     * address and `https://` otherwise, matching [moe.shimmerfly.nekoplayer.data.jellyfin.model.JellyfinCredentials].
     */
    val normalizedHttpUrlOrNull: HttpUrl?
        get() {
            val trimmed = serverUrl.trim().trimEnd('/')
            val hasScheme = trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)

            val withScheme = if (!hasScheme) {
                val host = "http://$trimmed".toHttpUrlOrNull()?.host?.lowercase()
                val scheme = if (host != null && isHttpAllowedHost(host)) "http" else "https"
                "$scheme://$trimmed"
            } else {
                trimmed
            }
            return withScheme.toHttpUrlOrNull()
        }

    /**
     * Returns the normalized server URL (without trailing slash).
     */
    val normalizedServerUrl: String
        get() = normalizedHttpUrlOrNull?.toString()?.trimEnd('/') ?: serverUrl.trim().trimEnd('/')

    /** Whether Android 17's local-network runtime permission is needed for this server. */
    val requiresLocalNetworkAccess: Boolean
        get() = normalizedHttpUrlOrNull?.host?.let(CloudStreamSecurity::isLocalServerHost) == true

    /**
     * Returns a validation error for connection setup, or null when the URL is acceptable.
     */
    fun connectionValidationError(requireHttps: Boolean = false): String? {
        val httpUrl = normalizedHttpUrlOrNull ?: return "Enter a valid server URL."
        if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) {
            return "Server URL must not include embedded credentials."
        }
        if (requireHttps && !httpUrl.isHttps) {
            return "Use an https:// server URL for Navidrome/Subsonic."
        }
        if (!httpUrl.isHttps && !isHttpAllowedHost(httpUrl.host.lowercase())) {
            return "Use https:// for remote Navidrome/Subsonic servers. HTTP is only allowed for local network addresses."
        }
        return null
    }

    private fun isHttpAllowedHost(host: String): Boolean =
        CloudStreamSecurity.isLocalServerHost(host)
}
