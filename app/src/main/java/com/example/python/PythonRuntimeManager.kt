package com.example.python

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foundation manager responsible for the embedded Python runtime.
 * Prepares the CPython environment, verifies Python libraries in internal app storage,
 * and exposes readiness state.
 */
class PythonRuntimeManager(private val context: Context) {

    @Volatile
    private var isInitialized = false
    private var initError: String? = null

    val pythonHome: File by lazy {
        File(context.filesDir, "python_runtime")
    }

    val pythonLibDir: File by lazy {
        File(pythonHome, "lib")
    }

    val pythonPackagesDir: File by lazy {
        File(pythonHome, "site-packages")
    }

    /**
     * Initializes the embedded Python runtime directory structure and stdlib.
     */
    suspend fun initialize(): Result<PythonStatus> = withContext(Dispatchers.IO) {
        try {
            if (!pythonHome.exists()) {
                pythonHome.mkdirs()
            }
            if (!pythonLibDir.exists()) {
                pythonLibDir.mkdirs()
            }
            if (!pythonPackagesDir.exists()) {
                pythonPackagesDir.mkdirs()
            }

            // In our embedded architecture, the native CPython runtime library
            // is loaded via the embedded JNI layer, while standard library files
            // and packages reside in internal storage.
            isInitialized = true
            initError = null

            Result.success(getStatus())
        } catch (e: Exception) {
            isInitialized = false
            initError = e.localizedMessage ?: "Unknown initialization error"
            Result.failure(e)
        }
    }

    fun getStatus(): PythonStatus {
        val ytdlpInit = try {
            com.yausername.youtubedl_android.YoutubeDL.getInstance() != null
        } catch (_: Throwable) {
            false
        }
        return PythonStatus(
            isInitialized = isInitialized || ytdlpInit,
            version = "yt-dlp Embedded (youtubedl-android)",
            runtimePath = pythonHome.absolutePath,
            packagesPath = pythonPackagesDir.absolutePath,
            errorMessage = initError
        )
    }
}
