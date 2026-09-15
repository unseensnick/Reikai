package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ReaderResumeTest {

    @ParameterizedTest(name = "read={0}, keep on read={1} keeps the position: {2}")
    @CsvSource(
        "false, false, true",
        "false, true, true",
        "true, false, false",
        "true, true, true",
    )
    fun `a chapter keeps its position unless it is read and positions on read chapters are not kept`(
        read: Boolean,
        preserveOnRead: Boolean,
        keeps: Boolean,
    ) {
        ReaderResume.keepsPosition(read, preserveOnRead) shouldBe keeps
    }
}
