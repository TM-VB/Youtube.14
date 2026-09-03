package com.example.domain.util

/**
 * Validates and formats time ranges for video trimming.
 */
object TimeValidator {

    private val TIME_REGEX = Regex("""^(?:(\d{1,2}):)?([0-5]?\d):([0-5]\d)$""")

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val message: String) : ValidationResult()
    }

    /**
     * Parses time string formatted as HH:MM:SS or MM:SS into total seconds.
     */
    fun parseToSeconds(timeStr: String?): Long? {
        if (timeStr.isNullOrBlank()) return null
        val trimmed = timeStr.trim()
        val parts = trimmed.split(":")
        return try {
            when (parts.size) {
                1 -> parts[0].toLong()
                2 -> {
                    val minutes = parts[0].toLong()
                    val seconds = parts[1].toLong()
                    minutes * 60 + seconds
                }
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val seconds = parts[2].toLong()
                    hours * 3600 + minutes * 60 + seconds
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Formats total seconds into standard HH:MM:SS format.
     */
    fun formatSeconds(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val sec = s % 60
        return String.format("%02d:%02d:%02d", hours, minutes, sec)
    }

    /**
     * Validates that start and end times are valid, start >= 0, and end > start.
     * Optionally checks if end <= durationSeconds.
     */
    fun validateTimeRange(
        startTime: String?,
        endTime: String?,
        durationSeconds: Long? = null
    ): ValidationResult {
        if (startTime.isNullOrBlank()) {
            return ValidationResult.Invalid("Start time cannot be empty")
        }
        if (endTime.isNullOrBlank()) {
            return ValidationResult.Invalid("End time cannot be empty")
        }

        val startSec = parseToSeconds(startTime)
            ?: return ValidationResult.Invalid("Start time must be in HH:MM:SS or MM:SS format")
        val endSec = parseToSeconds(endTime)
            ?: return ValidationResult.Invalid("End time must be in HH:MM:SS or MM:SS format")

        if (startSec < 0) {
            return ValidationResult.Invalid("Start time cannot be negative")
        }

        if (endSec <= startSec) {
            return ValidationResult.Invalid("End time must be greater than start time")
        }

        if (durationSeconds != null && durationSeconds > 0 && endSec > durationSeconds) {
            return ValidationResult.Invalid("End time exceeds video duration (${formatSeconds(durationSeconds)})")
        }

        return ValidationResult.Valid
    }
}
