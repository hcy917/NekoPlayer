package moe.shimmerfly.nekoplayer.data.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CloudTrackDownloadWorkerExtensionTest {

    @Test
    fun `audio-only containers reported under video or application types keep their extension`() {
        mapOf(
            "application/ogg" to "ogg",
            "video/ogg" to "ogg",
            "application/x-flac" to "flac",
            "video/webm" to "webm",
            "video/webm; codecs=opus" to "webm",
            "audio/x-matroska" to "mka",
            "video/x-matroska" to "mka",
            "audio/3gpp" to "3gp",
            "Video/3GPP" to "3gp",
        ).forEach { (type, expected) ->
            assertEquals(expected, CloudTrackDownloadWorker.extensionFor(type, null), "for $type")
        }
    }

    @Test
    fun `existing audio types keep their mappings`() {
        assertEquals("flac", CloudTrackDownloadWorker.extensionFor("audio/flac", null))
        assertEquals("m4a", CloudTrackDownloadWorker.extensionFor("video/mp4", null))
        assertEquals("opus", CloudTrackDownloadWorker.extensionFor("audio/opus", null))
        assertEquals("mp3", CloudTrackDownloadWorker.extensionFor("audio/mpeg", null))
        assertEquals("mp3", CloudTrackDownloadWorker.extensionFor("application/octet-stream", null))
    }

    @Test
    fun `stored mime type is used when the response has no content type`() {
        assertEquals("mka", CloudTrackDownloadWorker.extensionFor(null, "video/x-matroska"))
        assertEquals("mp3", CloudTrackDownloadWorker.extensionFor(null, null))
    }
}
