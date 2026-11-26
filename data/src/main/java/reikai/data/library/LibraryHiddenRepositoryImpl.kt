package reikai.data.library

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import reikai.domain.library.LibraryHiddenRepository
import reikai.domain.library.StoredType
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
    override fun subscribe(type: StoredType): Flow<Set<Long>> = when (type) {
        StoredType.MANGA -> queries.selectAllManga().subscribeToList()
        StoredType.NOVELS -> queries.selectAllNovel().subscribeToList()
    }.map { it.toSet() }.distinctUntilChanged()

    override suspend fun isHidden(type: StoredType, id: Long): Boolean = when (type) {
        StoredType.MANGA -> queries.isMangaHidden(id).awaitAsOneOrNull() != null
        StoredType.NOVELS -> queries.isNovelHidden(id).awaitAsOneOrNull() != null
    }

    override suspend fun hide(type: StoredType, ids: List<Long>) {
        if (ids.isEmpty()) return
        database.transaction {
            ids.forEach {
                when (type) {
                    StoredType.MANGA -> queries.insertManga(it)
                    StoredType.NOVELS -> queries.insertNovel(it)
                }
            }
        }
    }

    override suspend fun unhide(type: StoredType, ids: List<Long>) {
        if (ids.isEmpty()) return
        database.transaction {
            ids.forEach {
                when (type) {
                    StoredType.MANGA -> queries.deleteManga(it)
                    StoredType.NOVELS -> queries.deleteNovel(it)
                }
            }
        }
    }
}
