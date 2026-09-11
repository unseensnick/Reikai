package reikai.domain.merge

/**
 * Where a merge group's ranking puts [memberId] before any chapter is counted: its position in the
 * group's own override when there is one, else its source's position in the global preferred list,
 * else [Int.MAX_VALUE], leaving chapter coverage to decide. Both stitchers rank by this, and so does
 * [rankingStamps], so the stored stitch cannot be checked against a different rule than built it.
 */
fun <S> sourcePriority(memberId: Long, sourceId: S?, preferredSourceIds: List<S>, memberRanking: List<Long>): Int {
    val position = if (memberRanking.isNotEmpty()) {
        memberRanking.indexOf(memberId)
    } else {
        sourceId?.let(preferredSourceIds::indexOf) ?: -1
    }
    return position.takeIf { it >= 0 } ?: Int.MAX_VALUE
}

/** One library member of a merge group and what ranks it. Rows of a group arrive in its own member
 *  order, which is the override order whenever [overridden] is set. */
class RankedMember<S>(val groupId: Long, val memberId: Long, val sourceId: S, val overridden: Boolean)

/**
 * The part of each group's ranking that no chapter decides, as a value the stored stitch is stamped
 * with, so a reorder or a preferred-source change reads as stale the way a new chapter does. Members
 * are listed in tiers of equal priority, best first, since only that order reaches the stitch: two
 * rankings that order the members alike stamp alike, whichever list they came from, so a preference
 * edit that moves none of a group's members restitches nothing.
 */
fun <S> rankingStamps(members: List<RankedMember<S>>, preferredSourceIds: List<S>): Map<Long, String> =
    members.groupBy { it.groupId }.mapValues { (_, group) ->
        val memberRanking = if (group.first().overridden) group.map { it.memberId } else emptyList()
        group
            .groupBy({ sourcePriority(it.memberId, it.sourceId, preferredSourceIds, memberRanking) }, { it.memberId })
            .toSortedMap()
            .values
            .joinToString(";") { tier -> tier.sorted().joinToString(",") }
    }
