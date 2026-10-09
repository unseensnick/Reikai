package reikai.domain.merge

/**
 * A merged series has one chapter sort, filter and display setting: its settings owner's, the first
 * library member in stored source order (computeRelatedIds), so reordering the sources moves it. It is
 * deliberately not the library card's lead ([libraryLead]); see docs/dev/subsystems/merged-series.md. A change reaches
 * every member ([change]), a merge hands the owner's setting to the members joining ([adoptOwnerSetting])
 * and a member added back takes the group's ([rejoin]), so the stored values agree; reading through the
 * owner ([shown]) still holds for a group whose members disagree, such as one restored from an older backup.
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
        return opened.withOwnerChapterFlags(listOfNotNull(load(ownerId)), flagsOf, withFlags)
    }

    /** Runs [change], which writes [openedId]'s own setting, then gives every other member the result. */
    suspend fun change(openedId: Long, memberIds: List<Long>, change: suspend () -> Unit) {
        change()
        spread(openedId, memberIds)
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
}

/** [this] carrying the chapter flags of [members]' settings owner, the first of them; itself without members. */
inline fun <E> E.withOwnerChapterFlags(members: Collection<E>, flagsOf: (E) -> Long, withFlags: E.(Long) -> E): E {
    val owner = members.firstOrNull() ?: return this
    val flags = flagsOf(owner)
    return if (flags == flagsOf(this)) this else withFlags(flags)
}
