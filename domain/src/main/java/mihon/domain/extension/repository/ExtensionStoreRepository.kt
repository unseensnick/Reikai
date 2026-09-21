package mihon.domain.extension.repository

import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.flow.Flow
import mihon.domain.extension.model.ExtensionStore

interface ExtensionStoreRepository {
    suspend fun insert(indexUrl: String): Result<Unit>

    suspend fun insertFromPreference(indexUrl: String, name: String)

    suspend fun refreshAll()

    suspend fun fetchExtensions(): List<Extension.Available>

    // RK --> each store's own outcome, keyed by index URL, which fetchExtensions flattens away
    suspend fun fetchExtensionsByStore(): Map<String, Result<List<Extension.Available>>>
    // RK <--

    suspend fun getAll(): List<ExtensionStore>

    fun getAllAsFlow(): Flow<List<ExtensionStore>>

    fun getCountAsFlow(): Flow<Long>

    suspend fun remove(indexUrl: String)
}
