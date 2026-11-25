package reikai.presentation.components

import dev.icerock.moko.resources.StringResource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.library.ContentType
import tachiyomi.i18n.MR

class LibraryRefreshTest {

    @ParameterizedTest(name = "started={0}, {1}, category={2}")
    @MethodSource("messages")
    fun `a started or refused update says what it covers`(
        started: Boolean,
        chip: ContentType,
        category: Boolean,
        message: StringResource,
    ) {
        libraryRefreshMessage(started, chip, category) shouldBe message
    }

    private val novelRunning = listOf(ContentType.MANGA to flowOf(false), ContentType.NOVELS to flowOf(true))

    /** Only the novel update runs, so a chip is refreshing exactly when it shows novels. */
    @ParameterizedTest(name = "{0}")
    @CsvSource("MANGA, false", "NOVELS, true", "ALL, true")
    fun `a chip is refreshing while an update it shows runs`(chip: ContentType, refreshing: Boolean) = runTest {
        chipUpdating(flowOf(chip), novelRunning).first() shouldBe refreshing
    }

    companion object {
        @JvmStatic
        fun messages() = listOf(
            Arguments.of(true, ContentType.ALL, false, MR.strings.updating_both_libraries),
            Arguments.of(true, ContentType.ALL, true, MR.strings.updating_category),
            Arguments.of(true, ContentType.NOVELS, true, MR.strings.updating_category),
            Arguments.of(true, ContentType.NOVELS, false, MR.strings.updating_library),
            Arguments.of(false, ContentType.ALL, true, MR.strings.update_already_running),
        )
    }
}
