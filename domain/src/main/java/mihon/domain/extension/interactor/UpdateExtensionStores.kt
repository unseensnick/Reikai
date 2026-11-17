package mihon.domain.extension.interactor

import dev.zacsweers.metro.Inject
import mihon.domain.extension.repository.ExtensionStoreRepository
import reikai.domain.extension.StoreRefresh

@Inject
class UpdateExtensionStores(
    private val repository: ExtensionStoreRepository,
) {
    suspend operator fun invoke(): StoreRefresh { // RK
        return repository.refreshAll() // RK
    }
}
