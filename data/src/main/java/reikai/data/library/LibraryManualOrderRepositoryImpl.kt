package reikai.data.library

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import reikai.data.toContentType
import reikai.data.toDbValue
import reikai.domain.library.ContentType
import reikai.domain.library.LibraryManualOrderRepository
import reikai.domain.library.ManualOrderPosition
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LibraryManualOrderRepositoryImpl(
    private val database: Database,
) : LibraryManualOrderRepository {

    override fun subscribeAll(): Flow<List<ManualOrderPosition>> =
        database.library_manual_orderQueries.getAll(::mapPosition).subscribeToList()

    override suspend fun getCategory(categoryId: Long): List<ManualOrderPosition> =
        database.library_manual_orderQueries.getCategory(categoryId, ::mapPosition).awaitAsList()

    override suspend fun getForEntries(contentType: ContentType, entryIds: List<Long>): List<ManualOrderPosition> =
        database.library_manual_orderQueries
            .getForEntries(contentType.toDbValue(), entryIds, ::mapPosition)
            .awaitAsList()

    override suspend fun upsert(positions: List<ManualOrderPosition>) {
        database.transaction { positions.forEach { insert(it, it.categoryId) } }
    }

    override suspend fun replaceCategory(categoryId: Long, positions: List<ManualOrderPosition>) {
        database.transaction {
            database.library_manual_orderQueries.deleteCategory(categoryId)
            positions.forEach { insert(it, categoryId) }
        }
    }

    private suspend fun insert(position: ManualOrderPosition, categoryId: Long) {
        database.library_manual_orderQueries.insert(
            contentType = position.contentType.toDbValue(),
            entryId = position.entryId,
            categoryId = categoryId,
            position = position.position,
        )
    }

    override fun subscribeBases(): Flow<Map<Long, Long>> =
        database.library_manual_orderQueries.getAllBases { categoryId, sortFlags -> categoryId to sortFlags }
            .subscribeToList()
            .map { it.toMap() }

    override suspend fun setBase(categoryId: Long, sortFlags: Long) {
        database.library_manual_orderQueries.setBase(categoryId, sortFlags)
    }

    private fun mapPosition(contentType: Long, entryId: Long, categoryId: Long, position: Long) =
        ManualOrderPosition(contentType.toContentType(), entryId, categoryId, position)
}
