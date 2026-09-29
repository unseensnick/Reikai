package reikai.data.updateerror

import android.content.Context
import android.graphics.Color
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainInOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.File

/**
 * The one dump every update job writes, a section each. Manga wrote it alone, and rendering it here is
 * what lets the jobs share one file rather than each writing its own.
 */
class UpdateErrorLogTest {

    @TempDir
    lateinit var dir: File

    private val context by lazy {
        mockk<Context> {
            every { cacheDir } returns dir
            every { externalCacheDir } returns dir
            every { stringResource(MR.strings.content_type_manga) } returns "Manga"
            every { stringResource(MR.strings.gallery_update_checker) } returns "Gallery update checker"
            every { stringResource(MR.strings.content_type_novels) } returns "Novels"
            every { stringResource(MR.strings.library_errors_help, *anyVararg()) } returns "Help"
        }
    }

    @BeforeEach
    fun setUp() {
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        // The file holding the cache-dir helper builds a colour when it loads.
        mockkStatic(Color::class)
        every { Color.rgb(any<Int>(), any<Int>(), any<Int>()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        unmockkStatic(Color::class)
    }

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

    /** The jobs run on their own schedules, so one having nothing must leave no header. */
    @Test
    fun `an empty section leaves nothing behind in the log`() {
        updateErrorLogText(help = "Help", sections = listOf("", "\nNOVELS\n")) shouldBe "Help\n\nNOVELS\n"
    }

    @Test
    fun `each job's write keeps the other jobs' sections`() {
        val log = UpdateErrorLog(context)
        log.write(UpdateErrorSection.MANGA, listOf(UpdateErrorEntry("Berserk", "Source A", "HTTP 503")))
        log.write(UpdateErrorSection.GALLERIES, listOf(UpdateErrorEntry("Gallery", "Source B", "Timeout")))

        val dump = log.write(UpdateErrorSection.NOVELS, emptyList()).readText()

        dump.shouldContainInOrder("=== Manga ===", "- Berserk", "=== Gallery update checker ===", "- Gallery")
    }

    @Test
    fun `a clean manga run drops only the manga section`() {
        val log = UpdateErrorLog(context)
        log.write(UpdateErrorSection.MANGA, listOf(UpdateErrorEntry("Berserk", "Source A", "HTTP 503")))
        log.write(UpdateErrorSection.GALLERIES, listOf(UpdateErrorEntry("Gallery", "Source B", "Timeout")))

        val dump = log.write(UpdateErrorSection.MANGA, emptyList()).readText()

        dump shouldBe "Help\n\n=== Gallery update checker ===\n\n! Timeout\n  # Source B\n    - Gallery\n"
    }
}
