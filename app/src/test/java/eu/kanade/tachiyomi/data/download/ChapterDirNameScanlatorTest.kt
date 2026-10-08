package eu.kanade.tachiyomi.data.download

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * A "/" in a scanlator used to nest the chapter in a folder of its own, where the download was never found
 * again, so the scanlator is sanitized like the rest of the name while the older unsanitized names stay found.
 */
class ChapterDirNameScanlatorTest {

    private val provider = DownloadProvider(mockk(), mockk(), LibraryPreferences(InMemoryPreferenceStore()))

    @Test
    fun `a scanlator with a slash gives a folder name without one`() {
        provider.getChapterDirName(
            "Ch 1",
            "A/B",
            "u",
            disallowNonAsciiFilenames = false,
            enableChapterNameHash = false,
        ) shouldBe
            "A_B_Ch 1"
    }

    @Test
    fun `a download named by an unsanitized scanlator is still found`() {
        provider.getValidChapterDirNames("Ch 1", "A:B", "u") shouldContain "A:B_Ch 1"
    }
}
