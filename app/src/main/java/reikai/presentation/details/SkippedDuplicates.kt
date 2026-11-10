package reikai.presentation.details

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import reikai.domain.chapter.ChapterCopyMark
import reikai.domain.chapter.ChapterCopyMarkRepository
import reikai.domain.chapter.CopyMark
import reikai.domain.chapter.LastAnswer
import reikai.domain.chapter.duplicateCopyCounts
import reikai.domain.library.ContentType
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.lang.launchIO

/** The user's marks of one content type, by owner, then chapter url. */
typealias CopyMarks = Map<Long, Map<String, CopyMark>>

/** What the details list marks while it shows duplicates: each copy's set size, and the user's own marks. */
@Immutable
data class DuplicateMarks(val copies: Map<Long, Int>, val marks: Map<Long, CopyMark>)

/** A chapter as a copy mark is keyed: its owner and url. */
data class MarkableChapter(val ownerId: Long, val url: String)

/**
 * The details screen's side of the duplicate rule for one content type: the user's marks while "Show
 * duplicate chapters the reader skips" and [skipDuplicates], the type's own reader switch, are both on
 * (with that off the reader skips nothing), and the writes that change them, on [appScope] so leaving
 * the screen does not cut one off.
 */
class DuplicateMarking(
    private val type: ContentType,
    private val repository: ChapterCopyMarkRepository,
    show: Preference<Boolean>,
    skipDuplicates: Preference<Boolean>,
    scope: CoroutineScope,
    private val appScope: CoroutineScope,
) {
    val marks: StateFlow<CopyMarks?> =
        combine(show.changes(), skipDuplicates.changes(), repository.marks(type)) { shown, skipping, marks ->
            marks.takeIf { shown && skipping }
        }.stateIn(
            // A screen builds a marking per composition entry over its model's scope, which outlives that
            // entry, so an eager start leaves a collector behind each time.
            scope,
            SharingStarted.WhileSubscribed(),
            repository.marks(type).value.takeIf { show.get() && skipDuplicates.get() },
        )

    fun neverSkip(chapters: List<MarkableChapter>) {
        set(chapters) { CopyMark.NeverSkip }
    }

    /** One group per owner, since copies never cross entries; an owner with one chapter selected is left. */
    fun markAsCopies(chapters: List<MarkableChapter>) {
        val key = System.currentTimeMillis()
        set(chapters.groupBy { it.ownerId }.values.filter { it.size > 1 }.flatten()) { CopyMark.Group(key) }
    }

    fun clear(chapters: List<MarkableChapter>) {
        appScope.launchIO {
            chapters.groupBy { it.ownerId }.forEach { (owner, owned) ->
                repository.clear(type, owner, owned.map { it.url })
            }
        }
    }

    private fun set(chapters: List<MarkableChapter>, mark: () -> CopyMark) {
        appScope.launchIO { repository.set(type, chapters.map { ChapterCopyMark(it.ownerId, it.url, mark()) }) }
    }
}

/** The last [duplicateMarks] answer for one screen, since the rule runs several regex passes per chapter. */
internal class DuplicateMarksMemo<T> : LastAnswer<List<T>, CopyMarks, DuplicateMarks>()

/** [DuplicateMarks] over [this] by the arguments both readers pass the rule, so it marks what they skip. */
internal fun <T> List<T>.duplicateMarks(
    marks: CopyMarks,
    numberOf: (T) -> Double,
    nameOf: (T) -> String,
    originOf: (T) -> String?,
    ownerOf: (T) -> Long,
    urlOf: (T) -> String,
    idOf: (T) -> Long,
): DuplicateMarks {
    val markOf = { chapter: T -> marks[ownerOf(chapter)]?.get(urlOf(chapter)) }
    return DuplicateMarks(
        copies = duplicateCopyCounts(numberOf, nameOf, originOf, ownerOf, idOf, markOf),
        marks = mapNotNull { chapter -> markOf(chapter)?.let { idOf(chapter) to it } }.toMap(),
    )
}
