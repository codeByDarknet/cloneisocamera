package dev.mycam.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Formatters {
    /** 75_000 ms -> "1:15", 3_725_000 ms -> "1:02:05". */
    fun duration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Recording timer, always with hours: "00:01:15". */
    fun recordingTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        return String.format(
            Locale.US, "%02d:%02d:%02d",
            totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60
        )
    }

    fun fileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
    }

    fun dateTime(epochMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(epochMs))

    fun date(epochMs: Long): String =
        DateFormat.getDateInstance(DateFormat.LONG).format(Date(epochMs))

    fun time(epochMs: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))

    fun zoom(value: Float): String {
        val rounded = Math.round(value * 10) / 10f
        return when {
            rounded < 1f -> String.format(Locale.US, ".%d", Math.round(rounded * 10))
            rounded % 1f == 0f -> "${rounded.toInt()}"
            else -> String.format(Locale.US, "%.1f", rounded)
        }
    }
}
