package reikai.domain.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.presentation.recents.EmittingPreferenceStore

class NovelTtsEngineSelectionTest {

    private val preferences = NovelPreferences(EmittingPreferenceStore()).apply {
        readerTtsEngine().set("first")
        readerTtsVoice().set("voice")
    }

    @Test
    fun `switching to a different engine clears the voice`() {
        preferences.setReaderTtsEngine("second")

        preferences.readerTtsVoice().get() shouldBe ""
    }

    @Test
    fun `reselecting the same engine keeps the voice`() {
        preferences.setReaderTtsEngine("first")

        preferences.readerTtsVoice().get() shouldBe "voice"
    }

    @Test
    fun `selecting an engine stores it`() {
        preferences.setReaderTtsEngine("second")

        preferences.readerTtsEngine().get() shouldBe "second"
    }
}
