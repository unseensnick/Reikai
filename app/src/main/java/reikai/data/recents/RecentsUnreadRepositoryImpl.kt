package reikai.data.recents

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import reikai.domain.recents.RecentsUnreadRepository
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RecentsUnreadRepositoryImpl(
    private val database: Database,
) : RecentsUnreadRepository {

    override fun subscribeMangaIdsWithUnread(): Flow<Set<Long>> =
        database.recentsUnreadQueries.getMangaIdsWithUnread()
            .subscribeToList()
            .map { it.toSet() }

    override fun subscribeNovelIdsWithUnread(): Flow<Set<Long>> =
        database.recentsUnreadQueries.getNovelIdsWithUnread()
            .subscribeToList()
            .map { it.toSet() }
}
