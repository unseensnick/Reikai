package reikai.data.updateerror

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The one dump both update jobs write. Manga wrote it alone, and rendering it here is what lets the
 * two content types share one file rather than each writing its own.
 */
class UpdateErrorLogTest {

    @Test
    fun `a section groups its entries by error, then by source`() {
        val section = updateErrorSectionText(
            label = "Manga",
            entries = listOf(
                UpdateErrorEntry("Berserk", "Source A", "No chapters found"),
                UpdateErrorEntry("Vagabond", "Source A", "No chapters found"),
                UpdateErrorEntry("Vinland Saga", "Source B", "No chapters found"),
                UpdateErrorEntry("Blame", "Source A", "HTTP 503"),
            ),
        )

        section shouldBe """
            |
            |=== Manga ===
            |
            |! No chapters found
            |  # Source A
            |    - Berserk
            |    - Vagabond
            |  # Source B
            |    - Vinland Saga
            |
            |! HTTP 503
            |  # Source A
            |    - Blame
            |
        """.trimMargin()
    }

    @Test
    fun `a run that failed nothing renders no section at all`() {
        updateErrorSectionText(label = "Novels", entries = emptyList()) shouldBe ""
    }

    @Test
    fun `the log keeps the sections it was handed, in order`() {
        updateErrorLogText(help = "Help", sections = listOf("\nMANGA\n", "\nNOVELS\n")) shouldBe
            "Help\n\nMANGA\n\nNOVELS\n"
    }

    /** The two jobs run on their own schedules, so one type having nothing must leave no header. */
    @Test
    fun `an empty section leaves nothing behind in the log`() {
        updateErrorLogText(help = "Help", sections = listOf("", "\nNOVELS\n")) shouldBe "Help\n\nNOVELS\n"
    }
}
