package exh.source

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.pref.DelegateSourcePreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

/**
 * Which source a wrapped extension unwraps to: its own settings always, and the delegate's extras only
 * while delegated sources are on.
 */
class SourceHelpersTest {

    private val replaced: InjektScope = Injekt

    @AfterEach
    fun restoreInjekt() {
        Injekt = replaced
    }

    // The wrapper reads the switch through Injekt on every call, as source-api does in the app.
    private fun delegation(enabled: Boolean) {
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(
            DelegateSourcePreferences(
                InMemoryPreferenceStore(sequenceOf(InMemoryPreference("eh_delegate_sources", enabled, true))),
            ),
        )
    }

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

    @Test
    fun `a wrapped extension reaches its delegate while delegated sources are on`() {
        delegation(enabled = true)
        val delegate = DelegateFake()
        EnhancedHttpSource(PlainFake("Extension"), delegate).getMainSource<DelegateFake>() shouldBe delegate
    }

    @Test
    fun `a wrapped extension hides its delegate while delegated sources are off`() {
        delegation(enabled = false)
        EnhancedHttpSource(PlainFake("Extension"), DelegateFake()).getMainSource<DelegateFake>() shouldBe null
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

private class DelegateFake : PlainFake("Delegate")
