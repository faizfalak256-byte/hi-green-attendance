package com.example.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateTimeUtils {
    private val locale = Locale.US

    fun todayDateKey(): String =
        SimpleDateFormat("yyyy-MM-dd", locale).format(Date())

    fun currentMonthKey(): String =
        SimpleDateFormat("yyyy-MM", locale).format(Date())

    fun formatDateKey(timeMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd", locale).format(Date(timeMs))

    fun formatMonthKey(dateKey: String): String =
        if (dateKey.length >= 7) dateKey.substring(0, 7) else currentMonthKey()

    fun formatDisplayDate(dateKey: String): String {
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", locale).parse(dateKey) ?: return dateKey
            SimpleDateFormat("dd MMM yyyy", locale).format(parsed)
        } catch (_: Exception) {
            dateKey
        }
    }

    fun formatDisplayMonth(monthKey: String): String {
        return try {
            val parsed = SimpleDateFormat("yyyy-MM", locale).parse(monthKey) ?: return monthKey
            SimpleDateFormat("MMMM yyyy", locale).format(parsed)
        } catch (_: Exception) {
            monthKey
        }
    }

    fun formatTime(timeMs: Long): String {
        if (timeMs <= 0L) return "--:--"
        return SimpleDateFormat("hh:mm a", locale).format(Date(timeMs))
    }

    fun formatTime24(timeMs: Long): String {
        if (timeMs <= 0L) return ""
        return SimpleDateFormat("HH:mm", locale).format(Date(timeMs))
    }

    fun parseTimeOnDate(dateKey: String, hhMm24: String): Long? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", locale)
            sdf.isLenient = false
            sdf.parse("$dateKey $hhMm24")?.time
        } catch (_: Exception) {
            null
        }
    }

    fun formatDurationMinutes(minutes: Long): String {
        if (minutes <= 0L) return "0h 00m"
        val hrs = minutes / 60
        val mins = minutes % 60
        return String.format(locale, "%dh %02dm", hrs, mins)
    }

    fun formatHoursDecimal(hours: Double): String =
        String.format(locale, "%.1f hrs", hours)

    /**
     * Returns the YYYY-MM-DD cutoff date for the rolling 3-month retention policy.
     * Any attendance or OT record strictly before this dateKey is pruned automatically.
     */
    fun threeMonthCutoffDateKey(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -3)
        return SimpleDateFormat("yyyy-MM-dd", locale).format(cal.time)
    }

    /**
     * Returns the latest 3 month keys [current, current-1, current-2] in YYYY-MM format.
     */
    fun latestThreeMonthKeys(): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM", locale)
        return (0..2).map { offset ->
            val cal = Calendar.getInstance()
            cal.add(Calendar.MONTH, -offset)
            fmt.format(cal.time)
        }
    }
}
