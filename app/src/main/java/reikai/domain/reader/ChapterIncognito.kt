package reikai.domain.reader

import reikai.domain.source.SourceKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether writing a chapter's reading stays private, decided by the source of the entry that owns it,
 * as Mihon decides per source. Asked for the chapter being written, never once for the series: a merged
 * series spans sources, and a seamless window writes a neighbour's progress too. Cached per source for
 * the session, as Mihon's reader reads it once.
 */
class ChapterIncognito(
    private val sourceOf: suspend (ownerId: Long) -> SourceKey?,
    private val isIncognito: suspend (SourceKey?) -> Boolean,
) {
    private val bySource = ConcurrentHashMap<SourceKey, Boolean>()

    /** Whether a chapter owned by [ownerId] is read in incognito. */
    suspend fun of(ownerId: Long): Boolean {
        val source = sourceOf(ownerId) ?: return isIncognito(null)
        return bySource[source] ?: isIncognito(source).also { bySource[source] = it }
    }
}
