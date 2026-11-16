package mihon.domain.extension.interactor

import dev.zacsweers.metro.Inject
import mihon.domain.extension.repository.ExtensionStoreRepository
import reikai.domain.extension.RefusedStore

@Inject
class UpdateExtensionStores(
    private val repository: ExtensionStoreRepository,
) {
    suspend operator fun invoke(): List<RefusedStore> { // RK
        return repository.refreshAll() // RK
    }
}
