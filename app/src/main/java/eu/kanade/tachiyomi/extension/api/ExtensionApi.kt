package eu.kanade.tachiyomi.extension.api

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.model.Extension
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.repository.ExtensionStoreRepository
import reikai.domain.extension.hasSigningKey
import tachiyomi.core.common.util.lang.withIOContext

@Inject
@SingleIn(AppScope::class)
class ExtensionApi(
    private val repository: ExtensionStoreRepository,
    private val updateExtensionStores: UpdateExtensionStores,
    private val extensionUpdateNotifier: ExtensionUpdateNotifier,
) {

    suspend fun findExtensions(): List<Extension.Available> {
        return withIOContext { repository.fetchExtensions() }
    }

    // RK --> each store's own outcome, so a store that could not be read is not mistaken for an empty one
    suspend fun findExtensionsByStore(): Map<String, Result<List<Extension.Available>>> {
        return withIOContext { repository.fetchExtensionsByStore() }
    }
    // RK <--

    /**
     * @param installedExtensions Extensions already read by [eu.kanade.tachiyomi.extension.ExtensionManager],
     * loaded or not. Only their versions and signatures are read, so there's nothing to gain from reading them
     * a second time.
     */
    suspend fun checkForUpdates(installedExtensions: List<Extension.Installed>) {
        updateExtensionStores()

        val extensions = findExtensions()

        // RK: the stored keys decide a keyless store's listings (Extension.findListing)
        val storeKeys = repository.getAll().filter { it.hasSigningKey }.mapTo(HashSet()) { it.signingKey }
        val extensionsWithUpdate = installedExtensions.filter { it.findUpdate(extensions, storeKeys) != null }

        if (extensionsWithUpdate.isNotEmpty()) {
            extensionUpdateNotifier.promptUpdates(extensionsWithUpdate.map { it.name })
        }
    }
}
