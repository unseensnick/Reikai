package tachiyomi.domain.updates.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.updates.model.UpdatesWithRelations
import kotlin.time.Instant

interface UpdatesRepository {

    // RK: seriesMerging on every read, so a merged series with a hidden member hides as the library hides it
    suspend fun awaitWithRead(
        read: Boolean,
        after: Instant,
        limit: Long,
        seriesMerging: Boolean,
    ): List<UpdatesWithRelations>

    fun subscribeAll(
        after: Instant,
        limit: Long,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
        seriesMerging: Boolean, // RK
    ): Flow<List<UpdatesWithRelations>>

    // RK: seriesMerging
    fun subscribeWithRead(
        read: Boolean,
        after: Instant,
        limit: Long,
        seriesMerging: Boolean,
    ): Flow<List<UpdatesWithRelations>>
}
