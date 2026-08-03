package reikai.presentation.migrate.flow

import kotlinx.coroutines.flow.MutableStateFlow
import reikai.domain.entry.EntryId

/**
 * Carries a target picked on a browse screen back to the migration screen that asked for it.
 *
 * The two screens cannot talk directly: browsing a source in full is a separate screen that gets
 * pushed on top, and Voyager has no return value. So the pick is left here and collected by the
 * screen underneath once it is back on screen.
 *
 * This is a single consume-once slot rather than an event stream, and it lives outside both screens
 * rather than on either. Nothing is lost while the asking screen is off-composition, a pick that
 * arrives twice is still one pick, and a stale pick cannot be delivered to a screen it was not
 * meant for, since the reader checks the entry it belongs to.
 */
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
