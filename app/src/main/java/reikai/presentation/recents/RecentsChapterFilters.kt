package reikai.presentation.recents

import androidx.compose.runtime.Immutable
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
 * Whether reading has begun, which a read chapter satisfies by having been read. Taken from the stored
 * value rather than the displayed one: a row hides its progress once the chapter is read and rounds a
 * novel's hundredths down, and neither is a statement about whether the reader ever opened it.
 */
private val RecentsChapterState.hasStarted: Boolean
    get() = read || progress?.hasStarted == true

private val RecentsProgress.hasStarted: Boolean
    get() = when (this) {
        is RecentsProgress.Pages -> lastPageRead > 0L
        is RecentsProgress.Percent -> hundredths > 0L
    }
