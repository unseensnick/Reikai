package reikai.presentation.recents

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDate

/**
 * One rendered line, whichever mode built it. A date is a [LocalDate] rather than a formatted string
 * because the format depends on two preferences and is a Compose-side read; keeping it out is what lets
 * every policy stay a pure function. [Group.members] are items rather than rows for a plainer reason:
 * typing them as rows made every member accessor carry an unreachable branch.
 */
@Immutable
sealed interface RecentsRow {
    data class DateHeader(val date: LocalDate) : RecentsRow

    data class Entry(val item: RecentsItem) : RecentsRow

    /** One series' several chapters from one day, behind a single row. */
    data class Group(
        val key: String,
        val date: LocalDate,
        val members: List<RecentsItem>,
        val expanded: Boolean,
    ) : RecentsRow

    data class Child(val item: RecentsItem) : RecentsRow

    data class SectionHeader(val section: RecentsLaneKind) : RecentsRow

    /** Jumps to the single-lane mode for [section], so only the lanes that have one carry it. */
    data class SectionFooter(val section: RecentsLaneKind) : RecentsRow
}
