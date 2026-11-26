package reikai.domain.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import reikai.domain.entry.EntryId
import reikai.domain.entry.storedType

/** The hide tables in memory, [initial] hidden from the start. */
class FakeLibraryHiddenRepository(initial: Set<EntryId> = emptySet()) : LibraryHiddenRepository {

    val rows = MutableStateFlow(initial)

    override fun subscribe(type: StoredType): Flow<Set<Long>> =
        rows.map { set -> set.filter { it.storedType == type }.mapTo(HashSet()) { it.rawId } }

    override suspend fun isHidden(type: StoredType, id: Long): Boolean = EntryId.of(type, id) in rows.value

    override suspend fun hide(type: StoredType, ids: List<Long>) = rows.update { set ->
        set + ids.map { EntryId.of(type, it) }
    }

    override suspend fun unhide(type: StoredType, ids: List<Long>) = rows.update { set ->
        set - ids.map { EntryId.of(type, it) }.toSet()
    }
}
