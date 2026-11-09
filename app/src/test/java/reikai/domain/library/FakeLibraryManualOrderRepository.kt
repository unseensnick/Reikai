package reikai.domain.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** An in-memory [LibraryManualOrderRepository] that keeps the real one's replace and upsert rules. */
class FakeLibraryManualOrderRepository : LibraryManualOrderRepository {

    val positions = MutableStateFlow<List<ManualOrderPosition>>(emptyList())
    val bases = MutableStateFlow<Map<Long, Long>>(emptyMap())

    override fun subscribeAll(): Flow<List<ManualOrderPosition>> = positions

    override suspend fun getCategory(categoryId: Long): List<ManualOrderPosition> =
        positions.value.filter { it.categoryId == categoryId }

    override suspend fun getForEntries(contentType: ContentType, entryIds: List<Long>): List<ManualOrderPosition> =
        positions.value.filter { it.contentType == contentType && it.entryId in entryIds }

    override suspend fun upsert(positions: List<ManualOrderPosition>) {
        val replaced = positions.map { Triple(it.contentType, it.entryId, it.categoryId) }.toSet()
        this.positions.value = this.positions.value.filterNot {
            Triple(it.contentType, it.entryId, it.categoryId) in replaced
        } + positions
    }

    override suspend fun replaceCategory(categoryId: Long, positions: List<ManualOrderPosition>) {
        this.positions.value = this.positions.value.filterNot { it.categoryId == categoryId } +
            positions.map { it.copy(categoryId = categoryId) }
    }

    override fun subscribeBases(): Flow<Map<Long, Long>> = bases.map { it }

    override suspend fun setBase(categoryId: Long, sortFlags: Long) {
        bases.value = bases.value + (categoryId to sortFlags)
    }
}
