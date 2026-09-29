package reikai.data.novel

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

/** A status a source states as a number reads back as the same status once stored in words. */
class NovelStatusCodeTest {

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5, 6])
    fun `every known status survives the round trip`(code: Int) {
        NovelStatusCode.fromString(NovelStatusCode.toSourceString(code)) shouldBe code
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 99])
    fun `an unknown status has no words`(code: Int) {
        NovelStatusCode.toSourceString(code) shouldBe null
    }

    // Shared library and details code reads a novel's status through manga's table.
    @ParameterizedTest(name = "{0}")
    @MethodSource("codePairs")
    fun `a novel status code equals manga's code for the same status`(novelCode: Int, mangaCode: Int) {
        novelCode shouldBe mangaCode
    }

    companion object {
        @JvmStatic
        fun codePairs() = listOf(
            Arguments.of(NovelStatusCode.UNKNOWN, SManga.UNKNOWN),
            Arguments.of(NovelStatusCode.ONGOING, SManga.ONGOING),
            Arguments.of(NovelStatusCode.COMPLETED, SManga.COMPLETED),
            Arguments.of(NovelStatusCode.LICENSED, SManga.LICENSED),
            Arguments.of(NovelStatusCode.PUBLISHING_FINISHED, SManga.PUBLISHING_FINISHED),
            Arguments.of(NovelStatusCode.CANCELLED, SManga.CANCELLED),
            Arguments.of(NovelStatusCode.ON_HIATUS, SManga.ON_HIATUS),
        )
    }
}
