package moe.shimmerfly.nekoplayer.data.navidrome.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavidromeCredentialsTest {

    @Test
    fun `connectionValidationError accepts normalized https urls`() {
        val credentials = NavidromeCredentials(
            serverUrl = " https://music.example.com/subsonic/ ",
            username = "user",
            password = "pass"
        )

        assertNull(credentials.connectionValidationError())
        assertEquals("https://music.example.com/subsonic", credentials.normalizedServerUrl)
    }

    @Test
    fun `connectionValidationError accepts http urls for local network addresses`() {
        val credentials = NavidromeCredentials(
            serverUrl = "http://192.168.1.20:4533",
            username = "user",
            password = "pass"
        )

        assertNull(credentials.connectionValidationError())
        assertEquals(
            "http://192.168.1.20:4533",
            credentials.normalizedServerUrl
        )
    }

    @Test
    fun `connectionValidationError accepts local dns suffixes`() {
        listOf("jellyfin.lan", "media.local", "music.home.arpa").forEach { host ->
            val credentials = NavidromeCredentials("http://$host:4533", "user", "pass")

            assertNull(credentials.connectionValidationError())
            assertTrue(credentials.requiresLocalNetworkAccess)
        }
        assertFalse(
            NavidromeCredentials("https://music.example.com", "user", "pass")
                .requiresLocalNetworkAccess
        )
    }

    @Test
    fun `connectionValidationError rejects http urls for public hosts`() {
        val credentials = NavidromeCredentials(
            serverUrl = "http://music.example.com",
            username = "user",
            password = "pass"
        )

        assertEquals(
            "Use https:// for remote Navidrome/Subsonic servers. HTTP is only allowed for local network addresses.",
            credentials.connectionValidationError()
        )
    }

    @Test
    fun `connectionValidationError rejects embedded credentials`() {
        val credentials = NavidromeCredentials(
            serverUrl = "https://user:secret@music.example.com",
            username = "user",
            password = "pass"
        )

        assertEquals(
            "Server URL must not include embedded credentials.",
            credentials.connectionValidationError()
        )
    }

    @Test
    fun `connectionValidationError rejects http for public ipv6 literals`() {
        val credentials = NavidromeCredentials("http://[2001:db8::1]:4533", "user", "pass")

        assertNotNull(credentials.connectionValidationError())
        assertFalse(credentials.requiresLocalNetworkAccess)
    }

    @Test
    fun `connectionValidationError accepts http for private ipv6 and home hosts`() {
        listOf("http://[fd00::1]:4533", "http://nas.home:4533", "http://nas.internal:4533").forEach { url ->
            val credentials = NavidromeCredentials(url, "user", "pass")

            assertNull(credentials.connectionValidationError(), "expected $url to be allowed over http")
            assertTrue(credentials.requiresLocalNetworkAccess, "$url should require LAN access")
        }
    }

    @Test
    fun `normalization prepends https for public hosts and trims trailing slash`() {
        val credentials = NavidromeCredentials("music.example.com/", "user", "pass")

        assertEquals("https://music.example.com", credentials.normalizedServerUrl)
        assertNull(credentials.connectionValidationError())
        assertFalse(credentials.requiresLocalNetworkAccess)
    }

    @Test
    fun `normalization prepends http for local and vpn hosts`() {
        mapOf(
            "192.168.1.5:4533/" to "http://192.168.1.5:4533",
            "100.64.12.34:4533" to "http://100.64.12.34:4533",
            "navidrome.tailnet.ts.net:4533" to "http://navidrome.tailnet.ts.net:4533",
            "music.lan:4533" to "http://music.lan:4533",
            "musicbox:4533" to "http://musicbox:4533",
        ).forEach { (input, expected) ->
            val credentials = NavidromeCredentials(input, "user", "pass")

            assertEquals(expected, credentials.normalizedServerUrl)
            assertNull(credentials.connectionValidationError(), "expected $input to be accepted")
            assertTrue(credentials.requiresLocalNetworkAccess, "$input should require LAN access")
        }
    }

    @Test
    fun `schemeless url with subpath keeps the path`() {
        val credentials = NavidromeCredentials("music.example.com/subsonic/", "user", "pass")

        assertEquals("https://music.example.com/subsonic", credentials.normalizedServerUrl)
        assertNull(credentials.connectionValidationError())
    }

    @Test
    fun `schemeless url with embedded credentials is still rejected`() {
        val credentials = NavidromeCredentials("user:secret@music.example.com", "user", "pass")

        assertEquals(
            "Server URL must not include embedded credentials.",
            credentials.connectionValidationError()
        )
    }

    @Test
    fun `blank server url is rejected as invalid`() {
        assertEquals(
            "Enter a valid server URL.",
            NavidromeCredentials("  ", "user", "pass").connectionValidationError()
        )
    }
}
