package reikai.domain.merge

/**
 * A merged series has one chapter sort, filter and display setting: its settings owner's, the first
 * library member in stored source order (computeRelatedIds), so reordering the sources moves it. It is
 * deliberately not the library card's lead ([libraryLead]); see merge-system-rebuild.md. A change through
 * any member reaches every member ([change]) and a merge hands the owner's setting to the members joining
 * ([adoptOwnerSetting]), so the stored values agree; reading through the owner ([shown]) still holds for
 * a group whose members disagree, such as one restored from an older backup.
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
