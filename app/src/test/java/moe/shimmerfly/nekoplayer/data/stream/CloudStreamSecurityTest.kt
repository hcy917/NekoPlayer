package moe.shimmerfly.nekoplayer.data.stream

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudStreamSecurityTest {

    @Test
    fun `validateRangeHeader accepts standard ranges`() {
        val validation = CloudStreamSecurity.validateRangeHeader("bytes=0-1023")

        assertTrue(validation.isValid)
        assertTrue(validation.normalizedHeader == "bytes=0-1023")
        assertTrue(validation.startInclusive == 0L)
        assertTrue(validation.endInclusive == 1023L)
        assertFalse(validation.isSuffixRange)
    }

    @Test
    fun `validateRangeHeader accepts suffix ranges`() {
        val validation = CloudStreamSecurity.validateRangeHeader("bytes=-4096")

        assertTrue(validation.isValid)
        assertTrue(validation.isSuffixRange)
        assertTrue(validation.startInclusive == null)
        assertTrue(validation.endInclusive == 4096L)
    }

    @Test
    fun `validateRangeHeader rejects invalid formats`() {
        assertFalse(CloudStreamSecurity.validateRangeHeader("bytes=1-2,4-5").isValid)
        assertFalse(CloudStreamSecurity.validateRangeHeader("bytes=10-2").isValid)
        assertFalse(CloudStreamSecurity.validateRangeHeader("bytes=-").isValid)
    }

    @Test
    fun `isSafeRemoteStreamUrl blocks localhost`() {
        assertFalse(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "http://127.0.0.1:8000/audio.mp3",
                allowedHostSuffixes = setOf("127.0.0.1"),
                allowHttpForAllowedHosts = true
            )
        )
    }

    @Test
    fun `isSafeRemoteStreamUrl enforces host allowlist and scheme rules`() {
        assertTrue(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "https://m7.music.126.net/file.mp3",
                allowedHostSuffixes = setOf("music.126.net")
            )
        )
        assertTrue(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "http://m7.music.126.net/file.mp3",
                allowedHostSuffixes = setOf("music.126.net"),
                allowHttpForAllowedHosts = true
            )
        )
        assertFalse(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "http://evil.example.com/file.mp3",
                allowedHostSuffixes = setOf("music.126.net"),
                allowHttpForAllowedHosts = true
            )
        )
    }

    @Test
    fun `isSupportedAudioContentType only accepts audio-safe types`() {
        assertTrue(CloudStreamSecurity.isSupportedAudioContentType("audio/mpeg"))
        assertTrue(CloudStreamSecurity.isSupportedAudioContentType("application/octet-stream"))
        assertFalse(CloudStreamSecurity.isSupportedAudioContentType("text/html"))
        listOf("application/json", "text/plain", "video/quicktime").forEach { type ->
            assertFalse(CloudStreamSecurity.isSupportedAudioContentType(type), "$type should be rejected")
        }
    }

    @Test
    fun `isSupportedAudioContentType accepts audio-only containers reported under video or application types`() {
        listOf(
            "application/ogg",
            "video/ogg",
            "video/webm",
            "video/x-matroska",
            "video/3gpp",
            "application/x-flac",
            "video/webm; codecs=opus",
            "Video/WebM",
        ).forEach { type ->
            assertTrue(CloudStreamSecurity.isSupportedAudioContentType(type), "$type should be accepted")
        }
    }

    @Test
    fun `isLocalServerHost accepts local network and vpn hosts`() {
        listOf(
            "localhost",
            "127.0.0.1",
            "192.168.1.20",
            "10.0.0.5",
            "172.20.0.3",
            "100.101.102.103",
            "mynas",
            "nas.local",
            "nas.lan",
            "nas.home",
            "nas.internal",
            "nas.home.arpa",
            "box.tailnet.ts.net",
            "::1",
            "[::1]",
            "fd00::1",
            "fc12:3456::1",
            "fe80::abcd",
        ).forEach { host ->
            assertTrue(CloudStreamSecurity.isLocalServerHost(host), "$host should be local")
        }
    }

    @Test
    fun `isLocalServerHost rejects public hosts including public ipv6 literals`() {
        listOf(
            "music.example.com",
            "notlocal.example.org",
            "8.8.8.8",
            "100.32.1.2",
            "2001:db8::1",
            "[2001:db8::1]",
            "2606:4700::1111",
            "fc::1",
            "",
        ).forEach { host ->
            assertFalse(CloudStreamSecurity.isLocalServerHost(host), "'$host' should not be local")
        }
    }
}
