package reikai.novel.export

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Which chapters a picked run of a novel puts in its book, by the chapters it starts and ends on. */
class EpubRunTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a run holds the chapters from its first to its last`(
        case: String,
        onDevice: List<Long>,
        expected: List<Long>,
    ) {
        chaptersInRun(onDevice, EpubRun(position = 11, firstId = 11L, lastId = 13L)) { it } shouldBe expected
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Arguments.of("as picked", listOf(10L, 11L, 12L, 13L, 14L), listOf(11L, 12L, 13L)),
            // A chapter downloaded before the run once shifted every position by one.
            Arguments.of("after an earlier download", listOf(5L, 10L, 11L, 12L, 13L, 14L), listOf(11L, 12L, 13L)),
            Arguments.of("after its last was deleted", listOf(10L, 11L, 12L, 14L), emptyList<Long>()),
        )
    }
}
