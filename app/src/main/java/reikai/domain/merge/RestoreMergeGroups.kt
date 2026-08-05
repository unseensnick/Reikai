package reikai.domain.merge

import reikai.domain.library.ContentType

/**
 * Materialise backed-up merge groups against the library as it stands, in one pass, resolved against
 * ONE snapshot of local membership so the result cannot depend on write order.
 *
 * The backup is authoritative for the entries it names; local members it does not name keep their own
 * group while two or more remain. Order and the ranking flag come from the surviving local group,
 * since the backup format carries neither. Rules and history: docs/dev/plans/merge-system-rebuild.md.
 */
class RestoreMergeGroups(
    private val repository: MergeGroupRepository,
) {

    /**
     * [groups] is one list of already-resolved local ids per backed-up group, in ref order. Groups
     * with fewer than two resolvable members are dropped by the caller or here; either way they
     * cannot form a group.
     */
    suspend operator fun invoke(contentType: ContentType, groups: List<List<Long>>) {
        if (groups.isEmpty()) return
        val memberships = repository.getAllMemberships(contentType)

        // First backup group to name an entry keeps it: an entry can only be in one group, and
        // deciding that here rather than letting the writes fight means the result does not depend
        // on which order they happen to run in.
        val claimed = HashSet<Long>()
        val restored = groups.mapNotNull { group ->
            val ids = group.distinct().filterNot { it in claimed }
            ids.takeIf { it.size >= 2 }?.also { claimed += it }
        }
        if (restored.isEmpty()) return

        // What the local groups losing members will be left with, read BEFORE anything is written:
        // these members are not part of the restore, so their grouping should survive it.
        val remainders = memberships
            .filterKeys { it in claimed }
            .values.distinct()
            .mapNotNull { groupId ->
                val members = repository.getMembers(contentType, groupId)
                val keep = members.filterNot { it in claimed }
                keep.takeIf { it.size >= 2 }?.let {
                    // Same trunk rule the plans below apply, and for the same reason: a remainder
                    // whose leading source the restore just took away is no longer the order the
                    // user set, so presenting it as theirs would move the library cover and the
                    // details trunk onto a source they never put in front.
                    val keepsTrunk = members.firstOrNull() in it
                    it to (keepsTrunk && repository.getGroup(groupId)?.overrideSourceRanking == true)
                }
            }

        // Resolved BEFORE anything is written, like the remainders above: the first write deletes the
        // very group rows the rest would read their order and flag from.
        val plans = restored.map { ids ->
            val localGroupId = ids.firstNotNullOfOrNull { memberships[it] }
            val localOrder = localGroupId?.let { repository.getMembers(contentType, it) }.orEmpty()
            val local = localOrder.toHashSet()
            val named = ids.toHashSet()
            val ordered = localOrder.filter { it in named } + ids.filterNot { it in local }
            // The ranking is carried only while the group it came from still leads with the source the
            // user put in front. A backup group can span two local groups, taking some members from a
            // ranked one and leaving its trunk behind; carrying the flag there would present an order
            // nobody chose as the user's own, and the library cover and chapter trunk would follow it.
            val keepsTrunk = localOrder.firstOrNull()?.let { it in named } == true
            val override = keepsTrunk &&
                localGroupId?.let { repository.getGroup(it)?.overrideSourceRanking } == true
            ordered to override
        }

        plans.forEach { (ids, override) ->
            repository.materializeGroup(contentType, ids, overrideSourceRanking = override)
        }
        remainders.forEach { (ids, override) ->
            repository.materializeGroup(contentType, ids, overrideSourceRanking = override)
        }
    }
}
