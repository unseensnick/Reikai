package reikai.presentation.browse

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class SourceLanguageNameTest {

    private val context = mockk<Context>()

    @Test
    fun `a source declaring no language has no name`() {
        sourceLanguageName("", context) shouldBe null
    }

    @Test
    fun `a value Android cannot name is shown as declared`() {
        sourceLanguageName("Bahasa Melayu", context) shouldBe "Bahasa Melayu"
    }

    @Test
    fun `a language is named in its own language`() {
        sourceLanguageName("fr", context) shouldBe "Français"
    }

    @Test
    fun `the flagged row line is empty for a source declaring no language`() {
        sourceLanguageLabel("", context) shouldBe ""
    }
}
