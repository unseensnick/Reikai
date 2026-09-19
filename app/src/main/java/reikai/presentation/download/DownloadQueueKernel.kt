package reikai.presentation.download

import reikai.domain.library.ContentType

/** A queued chapter's state in the terms both downloaders share. */
enum class QueuedChapterStatus { QUEUED, DOWNLOADING, ERROR }

/** One queued chapter, as its downloader holds it. */
data class QueuedChapter(
    val seriesId: Long,
    val chapterId: Long,
    val status: QueuedChapterStatus,
)

/** A series' card label: the title and the name of the source it downloads from. */
data class QueuedSeriesLabel(val title: String, val sourceName: String)

/**
 * Everything one downloader reports to the queue screen. [chapters] is in the downloader's own order,
 * which is the order it downloads in. [activeSeries] are the series it is working on now, by that
 * downloader's own rule, and [completed] counts what it finished per series while the series stayed
 * queued.
 */
data class DownloadQueueSnapshot(
    val chapters: List<QueuedChapter>,
    val activeSeries: Set<Long>,
    val completed: Map<Long, Int>,
    val labels: Map<Long, QueuedSeriesLabel>,
) {
    companion object {
        val EMPTY = DownloadQueueSnapshot(emptyList(), emptySet(), emptyMap(), emptyMap())
    }
}

/**
 * One card per series, in the downloader's order. The total is what remains plus what finished, so
 * a cancelled chapter shrinks it. A series reads as failed only when every chapter left in it failed,
 * so one bad chapter does not mislabel a series still downloading. The current chapter of an active
 * series is the one downloading, or the next one while its downloader pauses between chapters.
 */
fun DownloadQueueSnapshot.toCards(contentType: ContentType): List<EntryDownloadCardUi> =
    chapters.groupBy { it.seriesId }.map { (seriesId, queued) ->
        val completedCount = completed[seriesId] ?: 0
        val active = seriesId in activeSeries
        val label = labels[seriesId]
        EntryDownloadCardUi(
            contentType = contentType,
            seriesId = seriesId,
            sourceName = label?.sourceName.orEmpty(),
            title = label?.title.orEmpty(),
            downloadedChapters = completedCount,
            totalChapters = queued.size + completedCount,
            status = when {
                active -> EntryDownloadCardStatus.DOWNLOADING
                queued.all { it.status == QueuedChapterStatus.ERROR } -> EntryDownloadCardStatus.ERROR
                else -> EntryDownloadCardStatus.QUEUED
            },
            currentChapterId = if (active) {
                (
                    queued.firstOrNull { it.status == QueuedChapterStatus.DOWNLOADING }
                        ?: queued.firstOrNull { it.status == QueuedChapterStatus.QUEUED }
                    )?.chapterId
            } else {
                null
            },
        )
    }

/**
 * The one list's order. The saved order decides which positions belong to which content type, and
 * each type fills its positions in its downloader's own order, so the list never shows an order a
 * downloader is not actually following. A series the saved order has not seen yet goes last.
 */
fun arrangeCards(
    savedKeys: List<String>,
    cardsByType: Map<ContentType, List<EntryDownloadCardUi>>,
): List<EntryDownloadCardUi> {
    val present = cardsByType.values.flatten().associateBy { it.cardKey }
    val remaining = cardsByType.mapValuesTo(LinkedHashMap()) { (_, cards) -> ArrayDeque(cards) }
    val slotted = savedKeys.mapNotNull { key ->
        present[key]?.let { remaining[it.contentType]?.removeFirstOrNull() }
    }
    return slotted + remaining.values.flatten()
}

/**
 * The new series order of each content type whose own order a list reorder moved. A type whose
 * series kept their relative order is left out: Mihon's reorder restarts the chapters it is
 * downloading, so moving a novel past a manga must not touch the manga downloader.
 */
fun seriesOrderChanges(
    cards: List<EntryDownloadCardUi>,
    cardKeysInOrder: List<String>,
): Map<ContentType, List<Long>> {
    val byKey = cards.associateBy { it.cardKey }
    val ordered = cardKeysInOrder.mapNotNull { byKey[it] }
    return ordered.groupBy({ it.contentType }, { it.seriesId })
        .filter { (type, wanted) -> wanted != cards.filter { it.contentType == type }.map { it.seriesId } }
}

/**
 * Reorders chapters within each series by [keyOf], keeping the series in their current order. Both
 * downloaders' sort goes through this, so Sort means the same thing for either type.
 */
fun <T, K : Comparable<K>> List<T>.sortedWithinSeries(
    seriesOf: (T) -> Long,
    keyOf: (T) -> K?,
    descending: Boolean,
): List<T> = groupBy(seriesOf).values.flatMap { group ->
    val sorted = group.sortedBy(keyOf)
    if (descending) sorted.reversed() else sorted
}
