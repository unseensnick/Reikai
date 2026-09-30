package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ChapterSortPickTest {

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `picking the mode already shown flips its direction`(shownDescending: Boolean) {
        ChapterSortPick.descendingAfter(NUMBER, shownDescending, picked = NUMBER) shouldBe !shownDescending
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `picking a new mode sorts ascending whichever way the list was shown`(shownDescending: Boolean) {
        ChapterSortPick.descendingAfter(NUMBER, shownDescending, picked = ALPHABET) shouldBe false
    }

    private companion object {
        const val NUMBER = 0x100L
        const val ALPHABET = 0x300L
    }
}
