package com.example.domain.validator

sealed class TimeValidationResult {
    data class Success(val startSeconds: Int, val endSeconds: Int, val formattedStart: String, val formattedEnd: String) : TimeValidationResult()
    data class Error(val message: String) : TimeValidationResult()
}

object TimeValidator {

    /**
     * Parses time string in formats: "HH:MM:SS", "MM:SS", or "SS" into total seconds.
     * Returns null if string format is invalid.
     */
    fun parseTimeToSeconds(timeStr: String): Int? {
        val trimmed = timeStr.trim()
        if (trimmed.isEmpty()) return null

        val parts = trimmed.split(":")
        return try {
            when (parts.size) {
                1 -> {
                    // Raw seconds: "90"
                    val s = parts[0].toInt()
                    if (s >= 0) s else null
                }
                2 -> {
                    // "MM:SS" -> "01:30"
                    val m = parts[0].toInt()
                    val s = parts[1].toInt()
                    if (m in 0..59 && s in 0..59) {
                        m * 60 + s
                    } else null
                }
                3 -> {
                    // "HH:MM:SS" -> "00:01:30"
                    val h = parts[0].toInt()
                    val m = parts[1].toInt()
                    val s = parts[2].toInt()
                    if (h >= 0 && m in 0..59 && s in 0..59) {
                        h * 3600 + m * 60 + s
                    } else null
                }
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * Formats total seconds to "HH:MM:SS".
     */
    fun formatSecondsToTimestamp(totalSeconds: Int): String {
        val safeSeconds = totalSeconds.coerceAtLeast(0)
        val hours = safeSeconds / 3600
        val minutes = (safeSeconds % 3600) / 60
        val seconds = safeSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * Validates start time and end time relative to video duration.
     */
    fun validate(startTimeStr: String, endTimeStr: String, videoDurationSeconds: Int? = null): TimeValidationResult {
        val startSec = parseTimeToSeconds(startTimeStr)
            ?: return TimeValidationResult.Error("Invalid start time format. Use HH:MM:SS (e.g. 00:00:00)")

        val endSec = parseTimeToSeconds(endTimeStr)
            ?: return TimeValidationResult.Error("Invalid end time format. Use HH:MM:SS (e.g. 00:01:30)")

        if (startSec < 0) {
            return TimeValidationResult.Error("Start time cannot be negative.")
        }

        if (startSec >= endSec) {
            return TimeValidationResult.Error("Start time must be less than end time ($startSec >= $endSec).")
        }

        if (videoDurationSeconds != null && videoDurationSeconds > 0) {
            if (startSec >= videoDurationSeconds) {
                return TimeValidationResult.Error("Start time exceeds video duration ($videoDurationSeconds s).")
            }
            if (endSec > videoDurationSeconds + 5) {
                // allow slight buffer, otherwise error
                return TimeValidationResult.Error("End time exceeds video duration ($videoDurationSeconds s).")
            }
        }

        return TimeValidationResult.Success(
            startSeconds = startSec,
            endSeconds = endSec,
            formattedStart = formatSecondsToTimestamp(startSec),
            formattedEnd = formatSecondsToTimestamp(endSec)
        )
    }
}
