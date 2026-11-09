package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tachiyomi.domain.chapter.service.ChapterRecognition

/**
 * A local manga chapter inside a volume folder is named with the folder in front ("Vol 02 - Ch 011"), and
 * its number is still the chapter's, never the volume's.
 */
class LocalVolumeChapterNumberTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "Vol 02 - Ch 011, 11.0",
        "Volume 3 - Chapter 25, 25.0",
        "Vol. 1 - 004, 4.0",
        "v02 - c011, 11.0",
    )
    fun `the chapter number is read past the volume in front`(name: String, number: Double) {
        ChapterRecognition.parseChapterNumber("Series", name, -1.0) shouldBe number
    }
}
