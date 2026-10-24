package moe.shimmerfly.nekoplayer.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.google.common.truth.Truth.assertThat
import moe.shimmerfly.nekoplayer.data.diagnostics.PerformanceMetrics
import moe.shimmerfly.nekoplayer.data.media.AudioMetadataReader
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AlbumArtUtilsMetricsTest {

    @TempDir
    lateinit var root: File

    private lateinit var context: Context
    private lateinit var retriever: MediaMetadataRetriever
    private lateinit var songFile: File

    @BeforeEach
    fun setUp() {
        PerformanceMetrics.resetForTest()
        // Android's decoder is stubbed on the JVM; unreadable bounds keep the cached bytes as-is.
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeByteArray(any(), any(), any(), any()) } answers {
            arg<BitmapFactory.Options>(3).outWidth = -1
            null
        }
        context = mockk()
        every { context.filesDir } returns root
        songFile = root.resolve("Song.mp3").apply { writeBytes(byteArrayOf(1)) }
        retriever = mockk(relaxed = true)
        mockkObject(MediaMetadataRetrieverPool, AudioMetadataReader)
        every { MediaMetadataRetrieverPool.acquire() } returns retriever
        every { MediaMetadataRetrieverPool.release(any()) } returns Unit
        AlbumArtUtils.setFolderAlbumArtPreference(false)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(BitmapFactory::class)
        unmockkObject(MediaMetadataRetrieverPool, AudioMetadataReader)
        PerformanceMetrics.resetForTest()
    }

    @Test
    fun ensureAlbumArtCachedFile_countsCachedArtworkAsHitWithoutExtracting() {
        val cachedFile = AlbumArtUtils.getCachedAlbumArtFile(context, SONG_ID)
            .apply { writeBytes(ByteArray(2048) { 7 }) }

        assertThat(AlbumArtUtils.ensureAlbumArtCachedFile(context, SONG_ID, songFile.path))
            .isEqualTo(cachedFile)

        val snapshot = PerformanceMetrics.snapshot()
        assertThat(snapshot.counters[PerformanceMetrics.Counters.ARTWORK_CACHE_HIT]).isEqualTo(1)
        assertThat(snapshot.counters).doesNotContainKey(PerformanceMetrics.Counters.ARTWORK_CACHE_MISS)
        assertThat(snapshot.timings).doesNotContainKey(PerformanceMetrics.Timings.ARTWORK_EXTRACT)
        verify(exactly = 0) { retriever.embeddedPicture }
    }

    @Test
    fun ensureAlbumArtCachedFile_countsMissAndRecordsFreshlyExtractedArtworkSize() {
        every { retriever.embeddedPicture } returns ByteArray(2048) { 7 }

        assertThat(AlbumArtUtils.ensureAlbumArtCachedFile(context, SONG_ID, songFile.path)).isNotNull()

        val snapshot = PerformanceMetrics.snapshot()
        assertThat(snapshot.counters[PerformanceMetrics.Counters.ARTWORK_CACHE_MISS]).isEqualTo(1)
        assertThat(snapshot.counters[PerformanceMetrics.Counters.ARTWORK_EXTRACTED_FRESH]).isEqualTo(1)
        assertThat(snapshot.counters).doesNotContainKey(PerformanceMetrics.Counters.ARTWORK_CACHE_HIT)
        assertThat(snapshot.maxes[PerformanceMetrics.Maxes.ARTWORK_BYTES]).isEqualTo(2048)
        assertThat(snapshot.timings[PerformanceMetrics.Timings.ARTWORK_EXTRACT]!!.count).isEqualTo(1)
    }

    @Test
    fun ensureAlbumArtCachedFile_doesNotCountNoArtworkMarkerAsHitOrMiss() {
        every { retriever.embeddedPicture } returns null
        every { AudioMetadataReader.read(songFile) } returns null

        // The first lookup finds nothing, extracts nothing and leaves a no-artwork marker.
        assertThat(AlbumArtUtils.ensureAlbumArtCachedFile(context, SONG_ID, songFile.path)).isNull()
        val firstLookup = PerformanceMetrics.snapshot()
        assertThat(firstLookup.counters[PerformanceMetrics.Counters.ARTWORK_CACHE_MISS]).isEqualTo(1)
        assertThat(firstLookup.counters).doesNotContainKey(PerformanceMetrics.Counters.ARTWORK_EXTRACTED_FRESH)
        assertThat(firstLookup.timings[PerformanceMetrics.Timings.ARTWORK_EXTRACT]!!.count).isEqualTo(1)

        PerformanceMetrics.resetForTest()
        assertThat(AlbumArtUtils.ensureAlbumArtCachedFile(context, SONG_ID, songFile.path)).isNull()

        val markerLookup = PerformanceMetrics.snapshot()
        assertThat(markerLookup.counters).isEmpty()
        assertThat(markerLookup.timings).isEmpty()
    }

    private companion object {
        const val SONG_ID = 321L
    }
}
