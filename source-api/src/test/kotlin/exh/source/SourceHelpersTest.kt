package exh.source

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Which source's settings a settings entry point opens, so a wrapped extension keeps its own. */
class SourceHelpersTest {

    @Test
    fun `an enhanced source over a configurable extension exposes that extension's settings`() {
        val extension = ConfigurableFake("Extension")
        EnhancedHttpSource(extension, PlainFake("Delegate")).configurableSource() shouldBe extension
    }

    @Test
    fun `a configurable source that is not wrapped exposes itself`() {
        val source = ConfigurableFake("Extension")
        source.configurableSource() shouldBe source
    }

    @Test
    fun `a source with no settings exposes none`() {
        PlainFake("Plain").configurableSource() shouldBe null
    }
}

private open class PlainFake(override val name: String) : HttpSource() {
    override val baseUrl = "https://example.org"
    override val lang = "en"
    override val supportsLatest = false
}

private class ConfigurableFake(name: String) :
    PlainFake(name),
    ConfigurableSource {
    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit
}
