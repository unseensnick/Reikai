package reikai.presentation.migrate.flow

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import reikai.domain.entry.EntryId

/**
 * Carries a target picked on a browse screen back to the migration screen that asked for it. The two
 * cannot talk directly: browsing a source in full is a pushed screen and Voyager has no return
 * value, so the pick is left here and collected once the asking screen is back on screen.
 * A single consume-once slot rather than an event stream, living outside both screens: nothing is
 * lost while the asking screen is off-composition, a pick that arrives twice is still one pick, and
 * the reader checks the entry it belongs to, so a stale pick cannot land on the wrong screen.
 */
@Inject
@SingleIn(AppScope::class)
class MigrationPickHandoff {

    private val pending = MutableStateFlow<Pick?>(null)

    /** Offer [targetRawId] as the target for [entryId]. Overwrites any pick not yet collected: the
     *  latest one is what the user just chose. */
    fun offer(entryId: EntryId, targetRawId: Long) {
        pending.value = Pick(entryId, targetRawId)
    }

    /** Take the pick for [entryId], or null. Taking it clears it, so it is applied exactly once. */
    fun take(entryId: EntryId): Long? {
        val pick = pending.value ?: return null
        if (pick.entryId != entryId) return null
        pending.value = null
        return pick.targetRawId
    }

    /** Drop anything uncollected, so a pick cannot resurface in a later, unrelated migration. */
    fun clear() {
        pending.value = null
    }

    private data class Pick(val entryId: EntryId, val targetRawId: Long)
}

/**
 * Why a manually picked target could not be applied, consumed once by the screen that shows it.
 * [MigrationPickHandoff.take] clears on read, so a pick that failed after being taken has nowhere to
 * go, and without this the screen stayed as it was with nothing said to the user. It lives beside the
 * handoff rather than on one screen model because every screen that takes a pick, from a result strip
 * or from the pushed browse screen, can fail to apply it the same ways.
 */
sealed interface PickOutcome {
    /** The picked row could not be read back (deleted, or its source went away). */
    data object Unavailable : PickOutcome

    /** The entry was picked as its own target; migrating onto itself would do nothing. */
    data object SameEntry : PickOutcome

    /** The target came back with no chapters, so there is nothing to migrate onto. */
    data object NoChapters : PickOutcome
}
