package reikai.presentation.recents

import reikai.domain.chapter.ReadingOrder
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.flaggedOnAnotherSource

/**
 * One of an entry's chapters, projected to what a target rule needs, built by each provider from its own
 * engine so the data access stays per type while the rules stay one rule. **The list is always ascending
 * reading order**, oldest first: every rule walks it forwards and the two engines' native orders disagree
 * (a merged manga list arrives newest-first), so a provider handing one over unsorted makes "the next
 * chapter" mean the previous one, except that the chapters the user hid come last. [read] folds in
 * what another source of a merge group already read.
 */
data class RecentsChapter(
    val id: Long,
    val read: Boolean,
)

/**
 * [chapters], in the order given, as the rules below read them: read when the copy is, or when a copy
 * on another source that the stored [stitch] places with it is, among [pooled], every member's
 * chapters. A provider projects the group list and the entry's own list both through here: a copy the
 * stitch dropped from the group list is still that chapter, and carrying only its own flag let the
 * own-source fallback reopen a chapter the group had finished. Hidden chapters go last, per
 * [ReadingOrder.hiddenLast], so the rules below open one only when nothing else is left.
 */
fun <T> recentsChapters(
    chapters: List<T>,
    pooled: List<T>,
    stitch: List<ChapterUnit>,
    id: (T) -> Long,
    read: (T) -> Boolean,
    isHidden: (T) -> Boolean,
): List<RecentsChapter> {
    val readElsewhere = flaggedOnAnotherSource(pooled, chapters, stitch, id, read)
    return ReadingOrder.hiddenLast(chapters, isHidden)
        .map { RecentsChapter(id(it), read(it) || id(it) in readElsewhere) }
}

/**
 * Resume over a merge group: reopen the recorded chapter while it is unfinished, else the earliest
 * chapter still unread, which is what the library's continue button opens too. Earliest rather than
 * the next one after it, because a series read from the middle leaves unread chapters behind the
 * bookmark that a forward-only answer can never reach. Null when [recordedId] is not in [chapters],
 * which happens when the cross-source stitch dropped that copy. A chapter another source of the group
 * has already read arrives here as read.
 */
fun resumeInGroup(chapters: List<RecentsChapter>, recordedId: Long): Long? {
    val index = chapters.indexOfFirst { it.id == recordedId }
    if (index < 0) return null
    if (!chapters[index].read) return recordedId
    return firstUnreadOf(chapters)
}

/**
 * The chapter a read row opens, asked of the group list first and this entry's own source second. The
 * second pass is not the same question twice: the stitch drops a chapter another source represents, so
 * a recorded chapter missing from [group] can still resume in its own list, and only that case pays
 * [ownSource]'s query. The last clause is the added lane's rule, and it is what makes every row the
 * caught-up filter kept resolve something instead of dying on a tap.
 */
suspend fun resumeTarget(
    group: List<RecentsChapter>,
    recordedId: Long,
    ownSource: suspend () -> List<RecentsChapter>,
): Long? = resumeInGroup(group, recordedId)
    ?: resumeInGroup(ownSource(), recordedId)
    ?: firstUnreadOf(group)

/**
 * The first chapter left to read, for a row that has no recorded chapter to resume from. One rule for
 * both engines: the library applies each entry's own chapter filters when it resolves the same thing
 * (manga through getNextUnread, novels through GetNextNovelChapter.awaitFirstUnreadInGroup),
 * and this surface deliberately does not, because a filter about what to list should not decide where
 * a newly added series starts.
 */
fun firstUnreadOf(chapters: List<RecentsChapter>): Long? = chapters.firstOrNull { !it.read }?.id

/**
 * The chapter a newly added row opens: the group's first unread, else the entry's own. The second pass
 * is for chapters the cross-source stitch drops, without which a merged row resolves nothing and the
 * tap dies; only then does it pay [ownSource]'s query. Both lists come through [recentsChapters], so
 * the fallback cannot reopen a chapter the group finished.
 */
suspend fun addedTarget(group: List<RecentsChapter>, ownSource: suspend () -> List<RecentsChapter>): Long? =
    firstUnreadOf(group) ?: firstUnreadOf(ownSource())

/**
 * The chapter a lane's rule picked for a row, with what the row is drawn from. [chapters] holds every
 * chapter a rule could name, so the picked id projects back into a row: the group's list plus any
 * own-source copy the stitch dropped. [readElsewhere] and [bookmarkedElsewhere] are the named chapters
 * flagged on another source of the group, so the row says what the details list says.
 */
class RecentsTarget<T>(
    val chapterId: Long,
    val chapters: Map<Long, T>,
    val stitch: List<ChapterUnit>,
    val pooled: List<T>,
    val readElsewhere: Set<Long>,
    val bookmarkedElsewhere: Set<Long>,
)

/**
 * Merge-aware on all three lanes: a collapsed row stands for the whole group, so it must not reopen
 * what another of its sources already read. An unmerged entry passes its own list and an empty stitch.
 * [group] and [ownSource] come in ascending reading order; only [ownSource]'s query is paid, and only
 * when the group list cannot answer. Each provider supplies its chapter reads and hidden rule.
 */
suspend fun <T> resolveRecentsTarget(
    lane: RecentsLane,
    group: List<T>,
    pooled: List<T>,
    stitch: List<ChapterUnit>,
    ownSource: suspend () -> List<T>,
    id: (T) -> Long,
    read: (T) -> Boolean,
    bookmark: (T) -> Boolean,
    isHidden: (T) -> Boolean,
): RecentsTarget<T>? {
    val chapters = group.associateByTo(mutableMapOf(), id)
    fun List<T>.forRules() = recentsChapters(this, pooled, stitch, id, read, isHidden)
    suspend fun ownSourceForRules() = ownSource().onEach { chapters[id(it)] = it }.forRules()

    val chapterId = when (lane) {
        is RecentsLane.Read -> resumeTarget(group.forRules(), lane.chapter.chapterId) { ownSourceForRules() }
        is RecentsLane.Updated -> lane.chapter.chapterId
        RecentsLane.Added -> addedTarget(group.forRules()) { ownSourceForRules() }
    } ?: return null
    // Over both lists, so a row naming a copy the stitch dropped says what the group says of it.
    val named = chapters.values.toList()
    return RecentsTarget(
        chapterId = chapterId,
        chapters = chapters,
        stitch = stitch,
        pooled = pooled,
        readElsewhere = flaggedOnAnotherSource(pooled, named, stitch, id, read),
        bookmarkedElsewhere = flaggedOnAnotherSource(pooled, named, stitch, id, bookmark),
    )
}
