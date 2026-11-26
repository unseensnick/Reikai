package reikai.domain.chapter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import reikai.domain.library.StoredType

/** Copy marks held in memory, empty unless a test sets some. */
class InMemoryChapterCopyMarks : ChapterCopyMarkRepository {
    private val byType = StoredType.entries.associateWith { MutableStateFlow(emptyMap<Long, Map<String, CopyMark>>()) }

    override fun marks(type: StoredType) = byType.getValue(type)

    override suspend fun loadedMarks(type: StoredType) = byType.getValue(type).value

    override suspend fun loadedMarks(type: StoredType, ownerId: Long) = loadedMarks(type)[ownerId].orEmpty()

    override suspend fun set(type: StoredType, marks: List<ChapterCopyMark>) {
        byType.getValue(type).update { current ->
            marks.fold(current) { acc, m -> acc + (m.ownerId to (acc[m.ownerId].orEmpty() + (m.url to m.mark))) }
        }
    }

    override suspend fun clear(type: StoredType, ownerId: Long, urls: List<String>) {
        byType.getValue(type).update { it + (ownerId to (it[ownerId].orEmpty() - urls.toSet())) }
    }

    override suspend fun report(type: StoredType) = emptyList<CopyMarkReport>()
}
