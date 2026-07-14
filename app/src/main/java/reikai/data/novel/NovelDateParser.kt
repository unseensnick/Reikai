package reikai.data.novel

import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale

/**
 * Parse an lnreader plugin's chapter `releaseTime` string into epoch millis for the `dateUpload`
 * column. Plugins emit inconsistent shapes (ISO instants, relative phrases like "3 days ago", and a
 * handful of locale date formats), so each shape is tried in turn and anything unrecognized falls back
 * to 0L (unknown, rendered as no date) rather than guessing. Ported from tsundoku's JsSource.
 */
object NovelDateParser {

    private val RELATIVE = Regex(
        """(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""",
        RegexOption.IGNORE_CASE,
    )

    private val DATE_FORMATS = listOf(
        "yyyy-MM-dd",
        "MMMM dd, yyyy",
        "MMMM d, yyyy",
        "MMM dd, yyyy",
        "MMM d, yyyy",
        "dd MMMM yyyy",
        "dd MMM yyyy",
        "dd/MM/yyyy",
        "MM/dd/yyyy",
    )

    fun parse(dateStr: String?, now: Long = System.currentTimeMillis()): Long {
        val trimmed = dateStr?.trim().orEmpty()
        if (trimmed.isEmpty()) return 0L

        // ISO-8601 instant (carries a time component).
        if (trimmed.contains("T")) {
            return runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrDefault(0L)
        }

        // Relative phrase ("X <unit>s ago"), measured back from now.
        RELATIVE.find(trimmed)?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return 0L
            val millis = when (match.groupValues[2].lowercase()) {
                "second" -> amount * 1_000L
                "minute" -> amount * 60_000L
                "hour" -> amount * 3_600_000L
                "day" -> amount * 86_400_000L
                "week" -> amount * 604_800_000L
                "month" -> amount * 2_592_000_000L // ~30 days
                "year" -> amount * 31_536_000_000L // ~365 days
                else -> return 0L
            }
            return now - millis
        }

        // Common absolute date formats (day-precision, device-local midnight).
        for (format in DATE_FORMATS) {
            val parsed = runCatching { SimpleDateFormat(format, Locale.US).parse(trimmed)?.time }.getOrNull()
            if (parsed != null) return parsed
        }

        return 0L
    }
}
