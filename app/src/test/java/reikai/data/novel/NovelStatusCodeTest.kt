package reikai.data.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
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
}
