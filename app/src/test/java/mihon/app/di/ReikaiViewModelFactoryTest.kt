package mihon.app.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the contract 33 screens now depend on: a model reaches its screen only if it joined the graph's
 * multibinding, and one that did not fails loudly rather than resolving to something else. Nothing in
 * the app owns that behaviour, so a metrox upgrade that changed it would surface only on device.
 */
class ReikaiViewModelFactoryTest {

    class Contributed : ViewModel()

    class NotContributed : ViewModel()

    private var builds = 0

    private val factory = ReikaiViewModelFactory(
        viewModelProviders = mapOf(
            Contributed::class to {
                builds++
                Contributed()
            },
        ),
        assistedFactoryProviders = emptyMap(),
        manualAssistedFactoryProviders = emptyMap(),
    )

    @Test
    fun `a contributed model is built by the provider the graph registered`() {
        val model = factory.create(Contributed::class, CreationExtras.Empty)

        (model is Contributed) shouldBe true
        builds shouldBe 1
    }

    @Test
    fun `a model that never joined the multibinding fails instead of resolving`() {
        shouldThrowAny { factory.create(NotContributed::class, CreationExtras.Empty) }
    }
}
