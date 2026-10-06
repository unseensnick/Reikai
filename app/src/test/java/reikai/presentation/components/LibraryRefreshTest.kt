package reikai.presentation.components

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import tachiyomi.i18n.MR

class LibraryRefreshTest {

    @Test
    fun `an update under All announces both libraries`() {
        libraryRefreshMessage(started = true, chip = ContentType.ALL, category = false) shouldBe
            MR.strings.updating_both_libraries
    }

    @Test
    fun `a category update under All names the category`() {
        libraryRefreshMessage(started = true, chip = ContentType.ALL, category = true) shouldBe
            MR.strings.updating_category
    }

    @Test
    fun `a refused update says one is already running`() {
        libraryRefreshMessage(started = false, chip = ContentType.ALL, category = true) shouldBe
            MR.strings.update_already_running
    }

    @Test
    fun `one content type's update is the library`() {
        libraryRefreshMessage(started = true, chip = ContentType.NOVELS, category = false) shouldBe
            MR.strings.updating_library
    }

    private val novelRunning = listOf(ContentType.MANGA to flowOf(false), ContentType.NOVELS to flowOf(true))

    @Test
    fun `an update on the type the chip hides is not refreshing`() = runTest {
        chipUpdating(flowOf(ContentType.MANGA), novelRunning).first() shouldBe false
    }

    @Test
    fun `an update on the type the chip shows is refreshing`() = runTest {
        chipUpdating(flowOf(ContentType.NOVELS), novelRunning).first() shouldBe true
    }

    @Test
    fun `either update is refreshing under All`() = runTest {
        chipUpdating(flowOf(ContentType.ALL), novelRunning).first() shouldBe true
    }
}
