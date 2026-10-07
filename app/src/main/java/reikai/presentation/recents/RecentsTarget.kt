package reikai.presentation.recents

import reikai.domain.chapter.ReadingOrder
import reikai.domain.entry.EntryId
import reikai.domain.merge.ChapterCopyRow
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.GroupMarks
import reikai.domain.reader.ChapterProgress

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
 * [chapters], in the order given, as the rules below read them: read as [marks], the group's answer over
 * every member's chapters, says. A provider projects the group list and the entry's own list both
 * through here: a copy the stitch dropped from the group list is still that chapter, and carrying only
 * its own flag let the own-source fallback reopen a chapter the group had finished. Hidden chapters go
 * last, per [ReadingOrder.hiddenLast], so the rules below open one only when nothing else is left.
 */
fun <T> recentsChapters(
    chapters: List<T>,
    marks: GroupMarks,
    id: (T) -> Long,
    read: (T) -> Boolean,
    isHidden: (T) -> Boolean,
): List<RecentsChapter> =
    ReadingOrder.hiddenLast(chapters, isHidden).map { RecentsChapter(id(it), marks.isRead(id(it), read(it))) }

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
fun firstUnreadOf(chapters: List<RecentsChapter>): Long? = ReadingOrder.nextToRead(chapters) { it.read }?.id

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
 * own-source copy the stitch dropped. [marks] answers read and bookmarked for the group, so the row says
 * what the details list says.
 */
class RecentsTarget<T>(
    val chapterId: Long,
    val chapters: Map<Long, T>,
    val stitch: List<ChapterUnit>,
    val pooled: List<T>,
    val marks: GroupMarks,
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
    // Over every member's chapters, which hold both lists below (an entry's own rows are among them
    // whenever it is merged), so one pass answers either.
    val marks = GroupMarks.of(pooled, pooled, stitch, id, read, bookmark)
    fun List<T>.forRules() = recentsChapters(this, marks, id, read, isHidden)
    suspend fun ownSourceForRules() = ownSource().onEach { chapters[id(it)] = it }.forRules()

    val chapterId = when (lane) {
        is RecentsLane.Read -> resumeTarget(group.forRules(), lane.chapter.chapterId) { ownSourceForRules() }
        is RecentsLane.Updated -> lane.chapter.chapterId
        RecentsLane.Added -> addedTarget(group.forRules()) { ownSourceForRules() }
    } ?: return null
    return RecentsTarget(chapterId, chapters, stitch, pooled, marks)
}

/**
 * One copy of a row's target chapter as its engine stores it. [owner] is the entry the copy belongs to,
 * not necessarily the row's, since a merged row resolves across the group; a download is filed under
 * its stored [ownerTitle] and [ownerSource]. Only the named copy's state and label are drawn.
 */
class RecentsTargetCopy(
    val owner: EntryId,
    val ownerTitle: String,
    val ownerSource: String,
    val name: String,
    val number: Double,
    val scanlator: String?,
    val url: String,
    val read: Boolean,
    val bookmark: Boolean,
    val progress: ChapterProgress,
)

/**
 * The row [lane] draws for this target, so the label, the state and the download control all describe
 * the chapter a tap opens. [project] answers every copy whose owner it can find, keyed by id, and runs
 * here rather than on the draw path; no row when it cannot answer the named chapter.
 */
internal suspend fun <T> RecentsTarget<T>.toTargetRow(
    lane: RecentsLane,
    id: (T) -> Long,
    project: suspend (List<T>) -> Map<Long, RecentsTargetCopy>,
    download: (chapterId: Long, copies: () -> List<ChapterCopyRow>) -> RecentsDownloadUi,
): RecentsTargetRow? {
    val named = chapters[chapterId] ?: return null
    val sameChapter = recentsRowCopies(named, stitch, pooled, id)
    val projected = project(sameChapter)
    val chapter = projected[chapterId] ?: return null
    val unitOf = stitch.associateBy { it.chapterId }
    val copies = sameChapter.mapNotNull { copy ->
        val copyId = id(copy)
        projected[copyId]?.let {
            val unit = unitOf[copyId] ?: soloUnit(copyId)
            ChapterCopyRow(chapterId, unit, it.ownerTitle, it.ownerSource, it.name, it.scanlator, it.url)
        }
    }
    return RecentsTargetRow(
        ref = ChapterRef(chapter.owner, chapterId),
        chapter = lane.chapterLabel(chapter.name, chapter.number),
        state = chapterState(
            read = marks.isRead(chapterId, chapter.read),
            bookmark = marks.isBookmarked(chapterId, chapter.bookmark),
            progress = chapter.progress,
        ),
        download = download(chapterId) { copies },
    )
}
