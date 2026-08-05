package reikai.domain.merge

import reikai.domain.library.ContentType

/**
 * Materialise backed-up merge groups against the library as it stands, in one pass.
 *
 * Restore used to call [MergeGroupRepository.merge] once per backup group, so each write landed on
 * whatever the previous one had left behind: two series that were separate in the backup came back
 * as one whenever the device already had a group bridging them. Backing up `{A,B}` and `{C,D}` and
 * restoring onto a library where A had been merged with C produced a single four-member group whose
 * card interleaved four sources, with nothing in the error log.
 *
 * Every group is therefore resolved against ONE snapshot of local membership, which removes the
 * order dependence, and each is then written with [MergeGroupRepository.materializeGroup], which
 * states the whole group rather than folding into an existing one.
 *
 * **The backup is authoritative for the entries it names**: an entry it groups leaves whatever local
 * group it was in. Local members the backup says nothing about keep their own group when at least
 * two of them remain, with their hand-set order and ranking, and are left standalone otherwise. So a
 * restore rearranges only the entries it actually describes.
 *
 * **It is authoritative for membership only.** The backup format carries neither member order nor
 * the per-group ranking flag (a separate, parked gap), so where the entries already had a local
 * group, that group's answer to both is the only one anybody has and it is carried across. Resetting
 * instead would flatten an order the user dragged into place, silently moving the library cover and
 * the details trunk onto whatever the global preferred-source list ranks first. Survivor and append
 * rule match [MergeGroupRepository.merge]: the group of the first named id that has one decides the
 * order and the flag, its members keep their relative order, and entries it did not contain go last.
 * Members of that group the backup does NOT name are not dragged along; they fall to the remainder
 * rule above, and the ranking is dropped when the one it led with is among them.
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

        // What the local groups losing members will be left with, read BEFORE anything is written,
        // and carrying their ranking: these members are not part of the restore, so their grouping
        // should survive it.
        val remainders = memberships
            .filterKeys { it in claimed }
            .values.distinct()
            .mapNotNull { groupId ->
                val keep = repository.getMembers(contentType, groupId).filterNot { it in claimed }
                keep.takeIf {
                    it.size >= 2
                }?.let { it to (repository.getGroup(groupId)?.overrideSourceRanking == true) }
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
