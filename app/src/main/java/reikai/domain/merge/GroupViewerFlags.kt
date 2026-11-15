package reikai.domain.merge

/**
 * A series' reader flags (reading mode, page layout, rendering mode, rotation) as both readers keep them.
 * Read from [ownerId], the settings owner [GroupChapterSettings] reads through, and written to every
 * member, so a reorder that moves the owner keeps them. Never simply the opened entry: a merged series
 * opens through whichever member owns the chapter, so flags kept there were lost on the next launch.
 */
class GroupViewerFlags private constructor(
    /** The group's library members in computeRelatedIds' order, the opened entry alone outside a group. */
    val memberIds: List<Long>,
) {
    val ownerId: Long get() = memberIds.first()

    /** Runs [write], one masked update, for every member. */
    suspend fun write(write: suspend (id: Long) -> Unit) {
        memberIds.forEach { write(it) }
    }

    /** One member's flags and when it was last read, in [memberIds]' order, for [chosen]. */
    class Member(val flags: Long, val lastReadAt: Long)

    companion object {
        fun of(openedId: Long, memberIds: Collection<Long>) =
            GroupViewerFlags(memberIds.toList().ifEmpty { listOf(openedId) })

        /**
         * Per field in [masks], the value the most recently read of [members] chose, the owner winning a
         * tie; a field every member leaves at 0 (follow the setting) is left out. What the upgrade that
         * gathers flags once kept on single members onto the whole group writes.
         */
        fun chosen(members: List<Member>, masks: List<Long>): Map<Long, Long> = masks.mapNotNull { mask ->
            val latest = members.filter { it.flags and mask != 0L }.maxByOrNull { it.lastReadAt }
            latest?.let { mask to (it.flags and mask) }
        }.toMap()
    }
}
