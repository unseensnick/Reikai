package reikai.domain.chapter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import reikai.domain.library.ContentType

/** Copy marks held in memory, empty unless a test sets some. */
class InMemoryChapterCopyMarks : ChapterCopyMarkRepository {
    private val byType = ContentType.entries.associateWith { MutableStateFlow(emptyMap<Long, Map<String, CopyMark>>()) }

    override fun marks(type: ContentType) = byType.getValue(type)

    override suspend fun set(type: ContentType, marks: List<ChapterCopyMark>) {
        byType.getValue(type).update { current ->
            marks.fold(current) { acc, m -> acc + (m.ownerId to (acc[m.ownerId].orEmpty() + (m.url to m.mark))) }
        }
    }

    override suspend fun clear(type: ContentType, ownerId: Long, urls: List<String>) {
        byType.getValue(type).update { it + (ownerId to (it[ownerId].orEmpty() - urls.toSet())) }
    }

    override suspend fun report(type: ContentType) = emptyList<CopyMarkReport>()
}
