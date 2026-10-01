package mihon.domain.extension.repository

import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.flow.Flow
import mihon.domain.extension.model.ExtensionStore

interface ExtensionStoreRepository {
    suspend fun insert(indexUrl: String): Result<Unit>

    suspend fun insertFromPreference(indexUrl: String, name: String)

    suspend fun upsert(store: ExtensionStore)

    suspend fun refreshAll()

    // RK: fetchExtensions removed, ExtensionManager reads fetchExtensionsByStore

    // RK --> each store's own outcome, keyed by index URL, so a failed store is told apart from an empty one
    suspend fun fetchExtensionsByStore(): Map<String, Result<List<Extension.Available>>>
    // RK <--

    suspend fun getAll(): List<ExtensionStore>

    fun getAllAsFlow(): Flow<List<ExtensionStore>>

    fun getCountAsFlow(): Flow<Long>

    suspend fun remove(indexUrl: String)
}
