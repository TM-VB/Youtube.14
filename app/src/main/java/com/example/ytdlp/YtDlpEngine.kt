package com.example.ytdlp

import android.content.Context
import com.example.domain.model.FormatInfo
import com.example.domain.model.PlaylistInfo
import com.example.domain.model.VideoInfo
import com.example.domain.model.VideoMetadata
import com.example.downloader.engine.YtDlpDownloadEngine
import com.example.downloader.engine.YtDlpMediaEngine
import kotlinx.coroutines.runBlocking

/**
 * Backward-compatible facade delegating all operations to the single source of truth:
 * [YtDlpDownloadEngine] (implementing [YtDlpMediaEngine]).
 *
 * All duplicate implementations of download, cookies, and process lifecycle
 * have been unified into [YtDlpDownloadEngine].
 */
object YtDlpEngine {

    @Volatile
    private var delegateEngine: YtDlpMediaEngine? = null

    private fun getEngine(context: Context? = null): YtDlpMediaEngine {
        val current = delegateEngine
        if (current != null) return current

        return synchronized(this) {
            delegateEngine ?: run {
                val appCtx = context?.applicationContext
                    ?: try {
                        val activityThread = Class.forName("android.app.ActivityThread")
                        val method = activityThread.getMethod("currentApplication")
                        method.invoke(null) as? Context
                    } catch (_: Throwable) {
                        null
                    }

                if (appCtx != null) {
                    YtDlpDownloadEngine.getInstance(appCtx).also { delegateEngine = it }
                } else {
                    throw IllegalStateException("YtDlpEngine requires Context for initialization.")
                }
            }
        }
    }

    /**
     * Initializes the underlying yt-dlp native environment.
     */
    fun init(context: Context): Result<Unit> {
        val engine = synchronized(this) {
            delegateEngine ?: YtDlpDownloadEngine.getInstance(context.applicationContext).also {
                delegateEngine = it
            }
        }
        return engine.init(context.applicationContext)
    }

    fun isReady(): Boolean = delegateEngine?.isReady() ?: false

    fun isPlaylistUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("list=") || lower.contains("/playlist") || lower.contains("/sets/")
    }

    suspend fun extractPlaylist(
        url: String,
        context: Context? = null,
        processId: String? = null
    ): Result<PlaylistInfo> {
        return getEngine(context).extractPlaylist(url, processId)
    }

    suspend fun extractInfo(
        url: String,
        processId: String? = null,
        context: Context? = null
    ): Result<VideoInfo> {
        return getEngine(context).extractInfo(url, processId)
    }

    suspend fun getFormats(
        url: String,
        processId: String? = null
    ): Result<List<FormatInfo>> {
        return getEngine().getFormats(url, processId)
    }

    fun fetchVideoInfo(url: String): Result<VideoMetadata> {
        return runBlocking {
            getEngine().fetchVideoInfo(url)
        }
    }

    fun cancel(processId: String) {
        runBlocking {
            try {
                delegateEngine?.cancel(processId)
            } catch (_: Throwable) {}
        }
    }

    fun getVersion(context: Context): String {
        return getEngine(context).getVersion(context)
    }

    fun updateEngine(context: Context): Result<String> {
        return runBlocking {
            getEngine(context).updateEngine(context)
        }
    }
}
