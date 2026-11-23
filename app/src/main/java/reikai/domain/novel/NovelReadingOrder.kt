package reikai.domain.novel

import reikai.domain.chapter.CopyMark
import reikai.domain.merge.ChapterGap
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.CopyToOpen
import reikai.domain.merge.DownloadTargets
import reikai.domain.merge.GroupChapterFlags
import reikai.domain.merge.MergeScope
import reikai.domain.merge.withOpenedChapter
import reikai.domain.novel.interactor.NovelReadingRows
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.reader.ChapterListFilters
import reikai.domain.reader.downloadedOrCurrent
import reikai.domain.reader.isForwardEligible
import reikai.domain.reader.navigableChapters
import reikai.domain.reader.neighbourChapter

/**
 * The order a novel is read in from [currentId], over everything already read from the database: the
 * merge group's rows ([reading]), and the settings the reader steps by. [opened] is the current chapter's
 * own row, for a group-scoped open the unified list does not show. [filters] are the novel's chapter-list
 * filters, null with none stored. [onDiskAmong] answers which of some chapters are downloaded.
 */
class NovelOrderInputs(
    val currentId: Long,
    val scope: MergeScope,
    val reading: NovelReadingRows,
    val opened: NovelChapter?,
    val readingOrder: Comparator<NovelChapter>,
    val isHidden: (NovelChapter) -> Boolean,
    val markOf: (NovelChapter) -> CopyMark?,
    val owners: Map<Long, Novel>,
    val onDiskAmong: (List<NovelChapter>, Map<Long, Novel>) -> Set<Long>,
    val skipRead: Boolean,
    val skipFiltered: Boolean,
    val skipDuplicates: Boolean,
    val downloadedOnly: Boolean,
    val filters: ChapterListFilters?,
)

/**
 * Where a novel session can step, as the reader and read aloud with no reader both page through it.
 * [currentId] is the copy opened, which a merged open moves onto a downloaded one ([copiesToOpen]).
 * [aheadIds] is [orderedIds] before Downloaded only narrows it, which download-ahead walks.
 */
data class NovelReadingOrder(
    val currentId: Long,
    val orderedIds: List<Long>,
    val aheadIds: List<Long>,
    val forwardEligibleIds: Set<Long>,
    val memberIds: List<Long>,
    val stitch: List<ChapterUnit>,
    val copiesToOpen: CopyToOpen<NovelChapter>?,
    val downloadTargets: DownloadTargets,
    val gapPresent: ChapterGap.Present,
) {

    /** The chapter a forward step from [id] lands on, null when there is none to step to. */
    fun chapterAfter(id: Long): Long? =
        orderedIds.neighbourChapter(orderedIds.indexOf(id), forward = true) { it in forwardEligibleIds }
}

/**
 * Source scope walks the opened novel's own chapters; group scope aggregates the merge group, putting
 * back a chapter the unified list does not show through [withOpenedChapter], the kernel the manga reader
 * shares. Paging follows [navigableChapters]; a forward step stops only where [isForwardEligible] lets it.
 */
fun novelReadingOrder(inputs: NovelOrderInputs): NovelReadingOrder = with(inputs) {
    val pooled = reading.pooled
    val stitch = reading.stitch
    val copies = reading.copies.takeIf { scope == MergeScope.Group }
    val current = copies?.idOf(currentId) ?: currentId
    val chapters = if (scope == MergeScope.Source) {
        reading.rows
    } else {
        withOpenedChapter(
            unified = reading.rows,
            opened = reading.rows.find { it.id == current } ?: pooled.find { it.id == current } ?: opened,
            stitch = stitch,
            id = { it.id },
            // A novel source lists its chapters oldest-first, so the merged list runs that way too.
            byNumber = compareBy { it.chapterNumber },
            restamp = { chapter, order -> chapter.copy(sourceOrder = order) },
        )
    }
    val sorted = chapters.sortedWith(readingOrder)
    val members = pooled.ifEmpty { sorted }
    val flags = GroupChapterFlags(scope, members, sorted, stitch, { it.id }, { it.read }, { it.bookmark }) {
        onDiskAmong(members, owners)
    }
    // Every copy is asked before duplicates go, so the copy a group keeps is one a forward step may land on.
    val eligible = if ((!skipRead && !skipFiltered) || filters == null) {
        sorted.mapTo(HashSet()) { it.id }
    } else {
        sorted.filterTo(HashSet()) { ch ->
            ch.id == current || flags.isForwardEligible(ch, skipRead, skipFiltered, filters)
        }.mapTo(HashSet()) { it.id }
    }
    val onDisk = if (downloadedOnly) onDiskAmong(sorted, owners) else null
    val opening = sorted.find { it.id == current }
    val inOrder = if (opening == null) {
        sorted.filterNot(isHidden)
    } else {
        sorted.navigableChapters(
            opening,
            isHidden = isHidden,
            isForwardEligible = { it.id in eligible },
            downloadedOnlyIds = { onDisk },
            skipDuplicates = skipDuplicates,
            numberOf = { it.chapterNumber },
            nameOf = { it.name },
            idOf = { it.id },
            originOf = { it.scanlator },
            ownerOf = { it.novelId },
            markOf = markOf,
        )
    }
    val shownCurrent = inOrder.find { it.id == current }
    val visible = if (onDisk != null && shownCurrent != null) {
        inOrder.downloadedOrCurrent(shownCurrent, { it.id }, onDisk)
    } else {
        inOrder
    }
    NovelReadingOrder(
        currentId = current,
        orderedIds = visible.map { it.id },
        aheadIds = inOrder.map { it.id },
        forwardEligibleIds = eligible,
        memberIds = reading.memberIds,
        stitch = stitch,
        copiesToOpen = copies,
        downloadTargets = reading.downloadTargets,
        gapPresent = sorted.gapPresent(),
    )
}
