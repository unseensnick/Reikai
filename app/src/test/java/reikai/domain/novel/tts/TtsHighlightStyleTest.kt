package reikai.domain.novel.tts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Which marks colour the spoken text, the rule the sheet and Settings offer the text colour row by. */
class TtsHighlightStyleTest {

    @Test
    fun `only a background mark colours the text`() {
        TtsHighlightStyle.entries.filter { it.recolorsText } shouldBe listOf(TtsHighlightStyle.BACKGROUND)
    }
}
