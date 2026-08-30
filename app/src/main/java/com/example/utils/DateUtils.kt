package com.example.utils

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object DateUtils {

    private val STANDARD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
    private val SHORT_DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("d MMM", Locale.US)

    private val PARSE_FORMATTERS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US),
        DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.US),
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US),
        DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.US),
        DateTimeFormatter.ofPattern("d/M/yyyy", Locale.US),
        DateTimeFormatter.ofPattern("dd-MMM", Locale.US),
        DateTimeFormatter.ofPattern("d-MMM", Locale.US),
        DateTimeFormatter.ofPattern("dd/MM", Locale.US)
    )

    /**
     * Parse date string from Excel sheet into "yyyy-MM-dd".
     * If the year is missing, it will assume the current year or default to 2026.
     */
    fun normalizeDate(dateStr: String): String? {
        val trimmed = dateStr.trim()
        if (trimmed.isEmpty()) return null

        for (formatter in PARSE_FORMATTERS) {
            try {
                if (!formatter.toString().contains("yyyy") && !formatter.toString().contains("year")) {
                    // Formatter doesn't parse year, append current year or 2026 to parse safely
                    val currentYear = LocalDate.now().year
                    val dateTemp = LocalDate.parse("$trimmed-$currentYear", DateTimeFormatter.ofPattern("${formatter.toString().replace("Value(", "").replace(")", "")}-yyyy", Locale.US))
                    return dateTemp.format(STANDARD_FORMATTER)
                } else {
                    val date = LocalDate.parse(trimmed, formatter)
                    return date.format(STANDARD_FORMATTER)
                }
            } catch (e: Exception) {
                // Continue trying other formatters
            }
        }

        // Custom manual fallback parsing if formatting fails
        return try {
            // Try to extract numbers manually
            val parts = trimmed.split(Regex("[-/\\s]"))
            if (parts.size >= 2) {
                val day = parts[0].toIntOrNull()
                val monthStr = parts[1]
                val year = if (parts.size >= 3) parts[2].toIntOrNull() else LocalDate.now().year

                if (day != null) {
                    val month = when (monthStr.uppercase(Locale.US)) {
                        "JAN", "JANUARY", "01", "1" -> 1
                        "FEB", "FEBRUARY", "02", "2" -> 2
                        "MAR", "MARCH", "03", "3" -> 3
                        "APR", "APRIL", "04", "4" -> 4
                        "MAY", "05", "5" -> 5
                        "JUN", "JUNE", "06", "6" -> 6
                        "JUL", "JULY", "07", "7" -> 7
                        "AUG", "AUGUST", "08", "8" -> 8
                        "SEP", "SEPTEMBER", "09", "9" -> 9
                        "OCT", "OCTOBER", "10" -> 10
                        "NOV", "NOVEMBER", "11" -> 11
                        "DEC", "DECEMBER", "12" -> 12
                        else -> null
                    }
                    if (month != null) {
                        val finalYear = if (year != null && year < 100) 2000 + year else year ?: LocalDate.now().year
                        return LocalDate.of(finalYear, month, day).format(STANDARD_FORMATTER)
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the start of the week (Monday) for a given date.
     */
    fun getWeekStartDate(date: LocalDate): LocalDate {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }

    /**
     * Get the end of the week (Sunday) for a given date.
     */
    fun getWeekEndDate(date: LocalDate): LocalDate {
        return date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    }

    /**
     * Determines whether to show current or upcoming week based on rules:
     * - If today is Monday to Saturday: Show/generate current week.
     * - If today is Sunday: Generate/show upcoming week.
     */
    fun getTargetWeekStartDate(): LocalDate {
        val today = LocalDate.now()
        return if (today.dayOfWeek == DayOfWeek.SUNDAY) {
            // Next week's Monday
            today.plusDays(1)
        } else {
            // Current week's Monday
            getWeekStartDate(today)
        }
    }

    fun formatDateToStandard(date: LocalDate): String {
        return date.format(STANDARD_FORMATTER)
    }

    fun parseStandardDate(dateStr: String): LocalDate {
        return LocalDate.parse(dateStr, STANDARD_FORMATTER)
    }

    fun formatToDisplay(dateStr: String): String {
        return try {
            val date = LocalDate.parse(dateStr, STANDARD_FORMATTER)
            date.format(DISPLAY_FORMATTER)
        } catch (e: Exception) {
            dateStr
        }
    }

    fun formatWeekRange(start: LocalDate, end: LocalDate): String {
        val startStr = start.format(SHORT_DISPLAY_FORMATTER)
        val endStr = end.format(SHORT_DISPLAY_FORMATTER)
        val year = start.year
        return "$startStr – $endStr $year"
    }

    fun getDayOfWeekName(dateStr: String): String {
        return try {
            val date = LocalDate.parse(dateStr, STANDARD_FORMATTER)
            date.dayOfWeek.name.lowercase(Locale.US).replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            ""
        }
    }
}
