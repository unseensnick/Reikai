package reikai.presentation.recents

import androidx.compose.runtime.Immutable
import reikai.domain.entry.EntryId
import reikai.domain.reader.ChapterProgress
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.manga.model.applyFilter

/**
 * The four chapter-state filters, as one value. The updated lane answers them in SQL, so this exists
 * for the lanes no query filters: a mixed feed that narrowed only its update rows would hide part of
 * itself and leave the rest, with the toolbar reporting a filter that reached one lane in three.
 */
@Immutable
data class RecentsChapterFilters(
    val unread: TriState = TriState.DISABLED,
    val started: TriState = TriState.DISABLED,
    val bookmarked: TriState = TriState.DISABLED,
    val downloaded: TriState = TriState.DISABLED,
) {
    val isActive: Boolean
        get() = this != NONE

    /**
     * Whether a chapter survives. [isDownloaded] is a lambda because answering it costs a queue and
     * disk lookup on the read lane, which nothing should pay while that filter is off.
     */
    fun matches(state: RecentsChapterState, isDownloaded: () -> Boolean): Boolean =
        applyFilter(unread) { !state.read } &&
            applyFilter(started) { state.hasStarted } &&
            applyFilter(bookmarked) { state.bookmark } &&
            applyFilter(downloaded, isDownloaded)

    companion object {
        val NONE = RecentsChapterFilters()
    }
}

/**
 * Everything that decides whether one row survives: the four chapter-state filters, plus whether a
 * series with nothing left to read is kept. Carried as one value so the render combine stays inside
 * the five-argument overload, and so the two rules are read at the same emission.
 */
@Immutable
data class RecentsRowGate(
    val filters: RecentsChapterFilters,
    val showRead: Boolean,
    val unread: Set<EntryId>,
) {
    /**
     * Whether [item] survives the show-read rule. Only the combined modes apply it, and only to the
     * two lanes that name a chapter: the added lane has none, so "read" says nothing about it, and
     * Updates and History are a record of what happened rather than a list of what to read next.
     */
    fun keeps(item: RecentsItem, mode: RecentsMode): Boolean {
        if (showRead || !mode.isCombined) return true
        return when (item.lane) {
            is RecentsLane.Read, is RecentsLane.Updated -> item.entryId in unread
            RecentsLane.Added -> true
        }
    }

    companion object {
        val NONE = RecentsRowGate(RecentsChapterFilters.NONE, showRead = true, unread = emptySet())
    }
}

/**
 * Whether reading has begun, which a read chapter satisfies by having been read. Taken from the stored
 * value rather than the displayed one: a row hides its progress once the chapter is read and rounds a
 * novel's hundredths down, and neither is a statement about whether the reader ever opened it.
 */
private val RecentsChapterState.hasStarted: Boolean
    get() = read || progress?.hasStarted == true

private val ChapterProgress.hasStarted: Boolean
    get() = when (this) {
        is ChapterProgress.Pages -> lastPageRead > 0L
        is ChapterProgress.Percent -> hundredths > 0L
    }
