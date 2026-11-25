package reikai.data.novel

import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale

/**
 * Parse an lnreader plugin's chapter `releaseTime` string into epoch millis, which `toNovelChapter`
 * turns into the chapter's upload date. Plugins emit inconsistent shapes (ISO instants, relative phrases like "3 days ago", and a
 * handful of locale date formats), so each shape is tried in turn and anything unrecognized falls back
 * to 0L (unknown, which the sync fills as it does for manga) rather than guessing. Ported from tsundoku's JsSource.
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

        // ISO-8601 instant (carries a time component). Anything else with a T, a capitalised month or a
        // zone-less datetime, falls through to the formats, which read its date.
        if (trimmed.contains("T")) {
            runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()?.let { return it }
        }

        // Relative phrase ("X <unit>s ago"), measured back from now.
        RELATIVE.find(trimmed)?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return 0L
            val unit = when (match.groupValues[2].lowercase()) {
                "second" -> 1_000L
                "minute" -> 60_000L
                "hour" -> 3_600_000L
                "day" -> 86_400_000L
                "week" -> 604_800_000L
                "month" -> 2_592_000_000L // ~30 days
                "year" -> 31_536_000_000L // ~365 days
                else -> return 0L
            }
            // A count reaching before the epoch, or past what a Long holds, is no date a plugin means
            val millis = runCatching { Math.multiplyExact(amount, unit) }.getOrNull() ?: return 0L
            return (now - millis).takeIf { it >= 0L } ?: 0L
        }

        // Common absolute date formats (day-precision, device-local midnight). Strict, so a month or day
        // out of range falls through to the next format instead of rolling over: a lenient dd/MM/yyyy
        // read 12/25/2024 as January 2026 and left MM/dd/yyyy unreachable.
        for (format in DATE_FORMATS) {
            val parsed = runCatching {
                SimpleDateFormat(format, Locale.US).apply { isLenient = false }.parse(trimmed)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }

        return 0L
    }
}
