package tachiyomi.domain.updates.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository
import kotlin.time.Instant

@Inject
class GetUpdates(
    private val repository: UpdatesRepository,
) {

    // RK --> seriesMerging, the library's merging switch, which decides whether a hidden member hides its series
    suspend fun await(read: Boolean, after: Instant, seriesMerging: Boolean): List<UpdatesWithRelations> {
        return repository.awaitWithRead(read, after, limit = 500, seriesMerging = seriesMerging)
    }
    // RK <--

    fun subscribe(
        instant: Instant,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
        seriesMerging: Boolean, // RK
    ): Flow<List<UpdatesWithRelations>> {
        return repository.subscribeAll(
            instant,
            limit = 500,
            unread = unread,
            started = started,
            bookmarked = bookmarked,
            hideExcludedScanlators = hideExcludedScanlators,
            includedCategories = includedCategories,
            excludedCategories = excludedCategories,
            seriesMerging = seriesMerging, // RK
        )
    }

    // RK --> seriesMerging
    fun subscribe(read: Boolean, after: Instant, seriesMerging: Boolean): Flow<List<UpdatesWithRelations>> {
        return repository.subscribeWithRead(read, after, limit = 500, seriesMerging = seriesMerging)
    }
    // RK <--
}
