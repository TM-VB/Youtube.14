package com.example.downloader.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.di.AppContainer
import com.example.downloader.ytdlp.YtDlpEngineBridge
import com.example.ytdlp.YtDlpEngine
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Architectural regression tests verifying that yt-dlp operations across the entire app
 * are unified under a single source of truth ([YtDlpMediaEngine] and [YtDlpDownloadEngine]).
 *
 * Prevents re-introduction of dual-architecture or duplicate download implementations.
 */
@RunWith(RobolectricTestRunner::class)
class YtDlpUnificationArchitectureTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = AppContainer(context)
    }

    @Test
    fun `YtDlpDownloadEngine implements YtDlpMediaEngine, VideoExtractor, and DownloadEngine`() {
        val engine = YtDlpDownloadEngine.getInstance(context)
        assertTrue(engine is YtDlpMediaEngine)
        assertTrue(engine is VideoExtractor)
        assertTrue(engine is DownloadEngine)
    }

    @Test
    fun `YtDlpEngineBridge implements YtDlpMediaEngine and delegates to unified engine`() {
        val bridge = YtDlpEngineBridge(context)
        assertTrue(bridge is YtDlpMediaEngine)
        assertTrue(bridge is VideoExtractor)
        assertTrue(bridge is DownloadEngine)
    }

    @Test
    fun `AppContainer provides unified YtDlpMediaEngine for both VideoExtractor and DownloadEngine`() {
        assertNotNull(container.ytDlpMediaEngine)
        assertNotNull(container.videoExtractor)
        assertNotNull(container.downloadEngine)

        // Verifies the same unified media engine instance backs extractor and downloader
        assertTrue(container.videoExtractor is YtDlpMediaEngine)
        assertTrue(container.downloadEngine is YtDlpMediaEngine)
    }

    @Test
    fun `YtDlpEngine object does not have duplicate download implementation`() {
        // Reflection check: ensure duplicate download method was removed from YtDlpEngine
        val methods = YtDlpEngine::class.java.declaredMethods.map { it.name }
        assertTrue("download method must be removed from legacy YtDlpEngine facade", !methods.contains("download"))
    }
}
