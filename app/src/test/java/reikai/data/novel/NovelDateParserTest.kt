package reikai.data.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale

/**
 * Parsing of an lnreader chapter `releaseTime` into `dateUpload`. Covers the three shapes plugins
 * actually emit (ISO instant, "X units ago", locale date) plus the unknown fallback that keeps a
 * chapter dateless instead of guessing. Absolute-date expectations are computed the same way the
 * parser does, so they hold in any device time zone.
 */
class NovelDateParserTest {

    @Test
    fun `iso instant parses to its epoch millisecond`() {
        NovelDateParser.parse("2025-01-05T12:00:00Z") shouldBe Instant.parse("2025-01-05T12:00:00Z").toEpochMilli()
    }

    @Test
    fun `relative phrase counts back from the supplied now`() {
        val now = 1_700_000_000_000L
        NovelDateParser.parse("3 days ago", now) shouldBe now - 3 * 86_400_000L
    }

    @Test
    fun `plural and singular units both parse`() {
        val now = 1_700_000_000_000L
        NovelDateParser.parse("1 hour ago", now) shouldBe now - 3_600_000L
    }

    @Test
    fun `iso date without a time uses the day format`() {
        val expected = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2025-01-05")!!.time
        NovelDateParser.parse("2025-01-05") shouldBe expected
    }

    @Test
    fun `month-name date format parses`() {
        val expected = SimpleDateFormat("MMMM d, yyyy", Locale.US).parse("January 5, 2025")!!.time
        NovelDateParser.parse("January 5, 2025") shouldBe expected
    }

    @Test
    fun `a month-first date past the twelfth is read month first`() {
        // Read leniently as day-first, month 25 rolled forward into January 2026.
        val expected = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2024-12-25")!!.time
        NovelDateParser.parse("12/25/2024") shouldBe expected
    }

    @Test
    fun `an ambiguous slash date stays day first`() {
        val expected = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2024-04-03")!!.time
        NovelDateParser.parse("03/04/2024") shouldBe expected
    }

    @Test
    fun `an out-of-range date is unknown`() {
        NovelDateParser.parse("2024-13-45") shouldBe 0L
    }

    @Test
    fun `null releaseTime is unknown`() {
        NovelDateParser.parse(null) shouldBe 0L
    }

    @Test
    fun `unrecognized string is unknown`() {
        NovelDateParser.parse("sometime soon") shouldBe 0L
    }
}
