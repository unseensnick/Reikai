package reikai.presentation.recents

import androidx.compose.runtime.Immutable
import reikai.domain.entry.EntryId
import reikai.presentation.browse.components.EntryDuplicateCardUi

/**
 * One entry the library already holds that an add might be duplicating. Carries its own [EntryId] so a
 * callback never rebuilds identity from the card's raw id, which overlaps across the two content types.
 */
@Immutable
data class RecentsDuplicate(val entry: EntryId, val card: EntryDuplicateCardUi)

/**
 * Everything the duplicate prompt needs, resolved by the provider that found them, so the engine holds
 * one neutral value instead of each type's own payload. [groupIdByRawId] is keyed the way the shared
 * dialog collapses same-group cards, by each duplicate's raw row id.
 */
@Immutable
data class RecentsDuplicates(
    val duplicates: List<RecentsDuplicate>,
    val groupIdByRawId: Map<Long, Long>,
    val suggestGroup: Boolean,
)
