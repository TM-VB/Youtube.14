package com.example.ffmpeg

import android.content.Context
import android.os.Build
import com.yausername.ffmpeg.FFmpeg

/**
 * Compatibility singleton providing access to system-wide FFmpeg initialization and device ABI info.
 */
object FFmpegManager {

    private var isInitialized = false

    fun init(context: Context): Result<Unit> {
        if (isInitialized) return Result.success(Unit)
        return try {
            FFmpeg.getInstance().init(context.applicationContext)
            isInitialized = true
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(if (t is Exception) t else Exception(t.message, t))
        }
    }

    fun isReady(): Boolean = isInitialized

    fun getDeviceAbis(): List<String> {
        return Build.SUPPORTED_ABIS.toList()
    }

    fun getPrimaryAbi(): String {
        return Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    }
}
