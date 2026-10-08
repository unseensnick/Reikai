package reikai.domain.merge

/**
 * A merged series has one chapter sort, filter and display setting, whichever member it is opened
 * through: its lead's. The lead is the first of the group's library members in stored source order, the
 * order computeRelatedIds lists them in, so reordering the sources moves it. A change made through any
 * member reaches every member ([change]) and a merge hands the lead's setting to the members joining
 * ([adoptLead]), so the stored values agree; reading through the lead ([shown]) still holds for a group
 * whose members disagree, such as one restored from an older backup. See merge-system-rebuild.md.
 */
open class GroupChapterSettings<E>(
    private val flagsOf: (E) -> Long,
    private val withFlags: E.(Long) -> E,
    private val load: suspend (id: Long) -> E?,
    private val write: suspend (ids: List<Long>, flags: Long) -> Unit,
) {

    /** [opened] carrying the setting of its group's lead, the first of [memberIds]. */
    suspend fun shown(opened: E, memberIds: List<Long>): E {
        val leadId = memberIds.firstOrNull() ?: return opened
        return opened.withLeadChapterFlags(listOfNotNull(load(leadId)), flagsOf, withFlags)
    }

    /** Runs [change], which writes [openedId]'s own setting, then gives every other member the result. */
    suspend fun change(openedId: Long, memberIds: List<Long>, change: suspend () -> Unit) {
        change()
        spread(openedId, memberIds)
    }

    /** After a merge: every member of [memberIds] takes the lead's setting. */
    suspend fun adoptLead(memberIds: List<Long>) {
        memberIds.firstOrNull()?.let { spread(it, memberIds) }
    }

    private suspend fun spread(fromId: Long, memberIds: List<Long>) {
        val others = memberIds.filter { it != fromId }
        if (others.isEmpty()) return
        val flags = load(fromId)?.let(flagsOf) ?: return
        write(others, flags)
    }
}

/** [this] carrying the chapter flags of [members]' lead, the first of them; itself without members. */
inline fun <E> E.withLeadChapterFlags(members: Collection<E>, flagsOf: (E) -> Long, withFlags: E.(Long) -> E): E {
    val lead = members.firstOrNull() ?: return this
    val flags = flagsOf(lead)
    return if (flags == flagsOf(this)) this else withFlags(flags)
}
