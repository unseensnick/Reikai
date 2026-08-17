package mihon.app.di.injekt

import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton

/**
 * Hands Metro-owned singletons back to Injekt, which stays as the runtime facade installed
 * extensions resolve against.
 *
 * A type listed here must have its registration deleted from the Injekt modules in the same
 * change. Registered in both places, the app runs with two instances and loses state silently
 * instead of crashing.
 */
@Inject
class MetroInteropModule(
    private val json: Json,
) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(json)
    }
}
