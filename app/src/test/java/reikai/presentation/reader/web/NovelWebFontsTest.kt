package reikai.presentation.reader.web

import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The face's address lands inside a quoted `url('...')` in the reader document's `<style>`, and a
 * restored backup decides the family it carries, so the address must never hold a character that
 * ends that token.
 */
class NovelWebFontsTest {

    @ParameterizedTest
    @ValueSource(strings = ["'", "\"", "\\", "\n", "\r"])
    fun `a face address never carries a character that ends its url token`(character: String) = runTest {
        NovelWebFonts().url(mockk(), "a${character}b")!! shouldNotContain character
    }
}
