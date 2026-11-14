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

    companion object {
        fun of(openedId: Long, memberIds: Collection<Long>) =
            GroupViewerFlags(memberIds.toList().ifEmpty { listOf(openedId) })
    }
}
