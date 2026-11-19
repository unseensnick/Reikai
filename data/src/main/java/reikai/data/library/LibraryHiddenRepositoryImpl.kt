package reikai.data.library

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import reikai.domain.library.ContentType
import reikai.domain.library.LibraryHiddenRepository
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LibraryHiddenRepositoryImpl(
    private val database: Database,
) : LibraryHiddenRepository {

    private val queries = database.library_hiddenQueries

    // Re-emits on the trigger's deletes too: SQLDelight notifies the tables a statement's triggers write.
    override fun subscribe(type: ContentType): Flow<Set<Long>> = when (type) {
        ContentType.MANGA -> queries.selectAllManga().subscribeToList()
        ContentType.NOVELS -> queries.selectAllNovel().subscribeToList()
        ContentType.ALL -> error(ALL_UNSUPPORTED)
    }.map { it.toSet() }.distinctUntilChanged()

    override suspend fun isHidden(type: ContentType, id: Long): Boolean = when (type) {
        ContentType.MANGA -> queries.isMangaHidden(id).awaitAsOneOrNull() != null
        ContentType.NOVELS -> queries.isNovelHidden(id).awaitAsOneOrNull() != null
        ContentType.ALL -> error(ALL_UNSUPPORTED)
    }

    override suspend fun hide(type: ContentType, ids: List<Long>) {
        if (ids.isEmpty()) return
        database.transaction {
            ids.forEach {
                when (type) {
                    ContentType.MANGA -> queries.insertManga(it)
                    ContentType.NOVELS -> queries.insertNovel(it)
                    ContentType.ALL -> error(ALL_UNSUPPORTED)
                }
            }
        }
    }

    override suspend fun unhide(type: ContentType, ids: List<Long>) {
        if (ids.isEmpty()) return
        database.transaction {
            ids.forEach {
                when (type) {
                    ContentType.MANGA -> queries.deleteManga(it)
                    ContentType.NOVELS -> queries.deleteNovel(it)
                    ContentType.ALL -> error(ALL_UNSUPPORTED)
                }
            }
        }
    }

    private companion object {
        const val ALL_UNSUPPORTED = "a hide is per content type"
    }
}
