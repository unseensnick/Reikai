package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.tts.TtsEngineInfo
import reikai.domain.novel.tts.TtsVoice
import java.util.Locale

/** The names the reader sheet and Settings both show for read-aloud's engine, languages and voice. */
class TtsOptionsTest {

    private val system = Locale.getDefault()

    // Language names come from the device's locale, so the test fixes one.
    @BeforeEach
    fun setUp() = Locale.setDefault(Locale.ENGLISH)

    @AfterEach
    fun tearDown() = Locale.setDefault(system)

    private val options = TtsOptions(
        engines = listOf(TtsEngineInfo("com.example.speech", "Example Speech")),
        voices = listOf(
            TtsVoice("fr-1", "Amelie", "fr-FR"),
            TtsVoice("de-1", "Hans", "de-DE"),
            TtsVoice("en-1", "Sam", "en-US"),
        ),
    )

    @Test
    fun `languages are listed by name in the device's language`() {
        options.languageNames().values.toList() shouldBe listOf("English", "French", "German")
    }

    @Test
    fun `the picked languages read in the list's order, not the order they were picked in`() {
        options.languagesLabel(linkedSetOf("fr", "en"), "All") shouldBe "English, French"
    }

    @Test
    fun `no picked language reads as all of them`() {
        options.languagesLabel(emptySet(), "All") shouldBe "All"
    }

    @Test
    fun `the engine list offers the system's own first`() {
        options.engineEntries("Default").keys.toList() shouldBe listOf("", "com.example.speech")
    }

    @Test
    fun `an engine no longer installed reads as its package name`() {
        options.engineLabel("com.example.gone", "Default") shouldBe "com.example.gone"
    }

    @Test
    fun `the voice list offers the engine's own first, then the picked languages' voices`() {
        options.voiceEntries(setOf("de"), "Default") shouldBe mapOf("" to "Default", "de-1" to "Hans")
    }

    @Test
    fun `a voice outside the picked languages still reads by its name`() {
        options.voiceLabel("fr-1", "Default") shouldBe "Amelie"
    }

    /** The empty id is the engine's own voice, which both pickers show by default. */
    @Test
    fun `the engine's own voice reads as the default`() {
        options.voiceLabel("", "Default") shouldBe "Default"
    }

    @Test
    fun `a voice the engine no longer offers reads as its id`() {
        options.voiceLabel("gone", "Default") shouldBe "gone"
    }

    /** The sheet and Settings both ask this, so one screen cannot offer the row the other hides. */
    @Test
    fun `one engine installed offers no engine choice`() {
        TtsOptions(engines = listOf(TtsEngineInfo("com.example.speech", "Speech"))).offersEngineChoice shouldBe false
    }

    @Test
    fun `two engines installed offer an engine choice`() {
        TtsOptions(
            engines = listOf(
                TtsEngineInfo("com.example.speech", "Speech"),
                TtsEngineInfo("com.example.voice", "Voice"),
            ),
        ).offersEngineChoice shouldBe true
    }

    @Test
    fun `voices in one language offer no language choice`() {
        TtsOptions(voices = listOf(TtsVoice("a", "A", "en-US"), TtsVoice("b", "B", "en-GB")))
            .offersLanguageChoice shouldBe false
    }

    @Test
    fun `voices in two languages offer a language choice`() {
        options.offersLanguageChoice shouldBe true
    }
}
