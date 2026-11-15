package reikai.domain.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** An in-memory [LibraryManualOrderRepository] that keeps the real one's replace and upsert rules. */
class FakeLibraryManualOrderRepository : LibraryManualOrderRepository {

    val positions = MutableStateFlow<List<ManualOrderPosition>>(emptyList())
    val bases = MutableStateFlow<Map<Long, Long>>(emptyMap())

    // Counted, since a write that restores an equal order is no change a StateFlow collector would see.
    val replacements = MutableStateFlow(0)

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
        replacements.update { it + 1 }
    }

    override suspend fun yieldToRestore() {
        val shift = (positions.value.maxOfOrNull { it.position } ?: return) + 1
        positions.value = positions.value.map { it.copy(position = it.position - shift) }
    }

    override fun subscribeBases(): Flow<Map<Long, Long>> = bases.map { it }

    override suspend fun setBase(categoryId: Long, sortFlags: Long) {
        bases.value = bases.value + (categoryId to sortFlags)
    }
}
