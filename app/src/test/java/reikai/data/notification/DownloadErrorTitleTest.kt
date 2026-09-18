package reikai.data.notification

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** Both downloaders title their error notification through [downloadErrorTitle]. */
class DownloadErrorTitleTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    fun `a download error names the series and chapter`(failure: Failure) {
        downloadErrorTitle(failure.entry, failure.chapter, hideAdult = false, isAdult = true) shouldBe
            "${failure.entry}: ${failure.chapter}"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    fun `a download error names neither the adult series nor its chapter while adult content is hidden`(
        failure: Failure,
    ) {
        downloadErrorTitle(failure.entry, failure.chapter, hideAdult = true, isAdult = true) shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    fun `a download error still names a series that is not adult while adult content is hidden`(failure: Failure) {
        downloadErrorTitle(failure.entry, failure.chapter, hideAdult = true, isAdult = false) shouldBe
            "${failure.entry}: ${failure.chapter}"
    }

    @Test
    fun `a download error with no chapter names the series alone`() {
        downloadErrorTitle("A Novel", null, hideAdult = false, isAdult = false) shouldBe "A Novel"
    }

    @Test
    fun `a download error with no series takes the generic title`() {
        downloadErrorTitle(null, "Chapter 3", hideAdult = false, isAdult = false) shouldBe null
    }

    data class Failure(val type: String, val entry: String, val chapter: String) {
        override fun toString() = type
    }

    companion object {
        @JvmStatic
        fun failures() = listOf(
            Failure("manga", "A Manga", "Chapter 3"),
            Failure("novel", "A Novel", "Chapter 12: The Gate"),
        )
    }
}
