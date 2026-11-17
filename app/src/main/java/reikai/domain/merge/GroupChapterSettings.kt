package reikai.domain.merge

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A flag word a merged series keeps once (its chapter settings, and its reader flags through the
 * ViewerFlagSettings subclasses): its settings owner's, the first library member in stored source order
 * (computeRelatedIds), so reordering the sources moves it, deliberately not the library card's lead
 * ([libraryRanking]); see docs/dev/subsystems/merged-series.md. A change reaches every member ([change]), a
 * merge hands the owner's word to the members joining ([adoptOwnerSetting]) and a member added back takes the
 * group's ([rejoin]); reading through the owner ([shown]) still holds for a group whose members disagree.
 */
open class GroupChapterSettings<E>(
    private val flagsOf: (E) -> Long,
    private val withFlags: E.(Long) -> E,
    private val load: suspend (id: Long) -> E?,
    private val write: suspend (ids: List<Long>, flags: Long) -> Unit,
) {

    /** [opened] carrying the setting of its group's settings owner, the first of [memberIds]. */
    suspend fun shown(opened: E, memberIds: List<Long>): E {
        val ownerId = memberIds.firstOrNull() ?: return opened
        return shownWith(opened, load(ownerId))
    }

    /** [opened] carrying [owner]'s setting, for a screen that watches the owner's row itself. */
    fun shownWith(opened: E, owner: E?): E = opened.withOwnerChapterFlags(listOfNotNull(owner), flagsOf, withFlags)

    /**
     * Runs [change] on [openedId] as it is shown now, which writes its own setting, then gives every other
     * member the result. One change at a time, each read fresh, so two quick picks made from one screen's
     * copy cannot undo each other: a flag word is written whole.
     */
    suspend fun change(openedId: Long, memberIds: List<Long>, change: suspend (shown: E) -> Unit) {
        changes.withLock {
            val opened = load(openedId) ?: return
            change(shown(opened, memberIds))
            spread(openedId, memberIds)
        }
    }

    /** After a merge: every member of [memberIds] takes the settings owner's setting. */
    suspend fun adoptOwnerSetting(memberIds: List<Long>) {
        memberIds.firstOrNull()?.let { spread(it, memberIds) }
    }

    /**
     * Runs [add], which may put [id] back in the library, then gives every library member of its group
     * the setting the group showed before. A member out of the library missed each [change] since it
     * left and can be the owner again once back; a merge inside [add] has by then handed its stale
     * setting to the others, which is why every member is written, not just [id].
     */
    suspend fun <T> rejoin(id: Long, mergeManager: EntryMergeManager, add: suspend () -> T): T {
        val before = mergeManager.groupLibraryMembers(id)
        val ownerId = before.firstOrNull()?.takeIf { id !in before }
        val flags = ownerId?.let { load(it) }?.let(flagsOf) ?: return add()
        return add().also {
            val after = mergeManager.groupLibraryMembers(id)
            if (id in after) write(after, flags)
        }
    }

    private suspend fun spread(fromId: Long, memberIds: List<Long>) {
        val others = memberIds.filter { it != fromId }
        if (others.isEmpty()) return
        val flags = load(fromId)?.let(flagsOf) ?: return
        write(others, flags)
    }

    private companion object {
        // One for every settings word of both types: these writes are rare, so nothing needs a finer lock.
        val changes = Mutex()
    }
}

/** [this] carrying the chapter flags of [members]' settings owner, the first of them; itself without members. */
inline fun <E> E.withOwnerChapterFlags(members: Collection<E>, flagsOf: (E) -> Long, withFlags: E.(Long) -> E): E {
    val owner = members.firstOrNull() ?: return this
    val flags = flagsOf(owner)
    return if (flags == flagsOf(this)) this else withFlags(flags)
}
