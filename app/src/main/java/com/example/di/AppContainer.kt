package com.example.di

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadTaskDao
import com.example.data.repository.DownloadRepository
import com.example.data.storage.AndroidStorageManager
import com.example.downloader.DownloadManager
import com.example.downloader.engine.DownloadEngine
import com.example.downloader.engine.MediaProcessor
import com.example.downloader.engine.StorageManager
import com.example.downloader.engine.VideoExtractor
import com.example.downloader.ffmpeg.FFmpegManager
import com.example.downloader.ytdlp.YtDlpEngineBridge
import com.example.python.PythonRuntimeManager

/**
 * Dependency container providing singletons and clean abstraction boundaries.
 * Enables decoupling components without heavy reflection or build-time code generation.
 */
class AppContainer(private val context: Context) {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(context)
    }

    val appSettings: com.example.data.settings.AppSettings by lazy {
        com.example.data.settings.AppSettings.getInstance(context)
    }

    val downloadTaskDao: DownloadTaskDao by lazy {
        database.downloadTaskDao()
    }

    val downloadRepository: DownloadRepository by lazy {
        DownloadRepository(downloadTaskDao)
    }

    val storageManager: StorageManager by lazy {
        AndroidStorageManager(context)
    }

    val pythonRuntimeManager: PythonRuntimeManager by lazy {
        PythonRuntimeManager(context)
    }

    val ffmpegManager: FFmpegManager by lazy {
        FFmpegManager(context)
    }

    val mediaProcessor: MediaProcessor by lazy {
        ffmpegManager
    }

    val ytDlpMediaEngine: com.example.downloader.engine.YtDlpMediaEngine by lazy {
        com.example.downloader.engine.YtDlpDownloadEngine.getInstance(context)
    }

    val ytDlpEngineBridge: YtDlpEngineBridge by lazy {
        YtDlpEngineBridge(context, ytDlpMediaEngine)
    }

    val videoExtractor: VideoExtractor by lazy {
        ytDlpMediaEngine
    }

    val formatProvider: com.example.downloader.engine.FormatProvider by lazy {
        com.example.downloader.engine.DefaultFormatProvider()
    }

    val downloadEngine: DownloadEngine by lazy {
        ytDlpMediaEngine
    }

    val downloadManager: DownloadManager by lazy {
        DownloadManager.getInstance(context)
    }
}
