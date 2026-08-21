package mihon.app.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the contract every screen depends on: a model reaches its screen only if it joined the graph's
 * multibinding, and one that did not fails loudly rather than resolving to something else. Both halves
 * matter, because the assisted half is the larger one: 33 models are keyed directly, while 29 factory
 * interfaces serve the screens that pass a value at the call site. Nothing in the app owns this
 * behaviour, so a metrox upgrade that changed it would surface only on device.
 */
class ReikaiViewModelFactoryTest {

    class Contributed : ViewModel()

    class NotContributed : ViewModel()

    class Assisted(val id: Long) : ViewModel()

    fun interface AssistedFactory : ManualViewModelAssistedFactory {
        fun create(id: Long): Assisted
    }

    fun interface UncontributedFactory : ManualViewModelAssistedFactory {
        fun create(): Assisted
    }

    private var builds = 0

    private val factory = ReikaiViewModelFactory(
        viewModelProviders = mapOf(
            Contributed::class to {
                builds++
                Contributed()
            },
        ),
        assistedFactoryProviders = emptyMap(),
        manualAssistedFactoryProviders = mapOf(
            AssistedFactory::class to { AssistedFactory { id -> Assisted(id) } },
        ),
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

    @Test
    fun `a contributed assisted factory builds its model with the value from the call site`() {
        val model = factory.createManuallyAssistedFactory(AssistedFactory::class)().create(id = 7L)

        model.id shouldBe 7L
    }

    @Test
    fun `an assisted factory that never joined the multibinding fails instead of resolving`() {
        shouldThrowAny { factory.createManuallyAssistedFactory(UncontributedFactory::class) }
    }
}
