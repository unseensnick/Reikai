package reikai.presentation.recents

import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.datetime.LocalDate
import reikai.domain.entry.EntryId

/*
 * The four modes, as pure functions over the one ordered stream the engine emits. Nothing here re-sorts
 * that stream: the kernel owns the order, and a policy re-sorting it would be a second opinion nobody
 * rules on. What each policy does own is its collapse scope (see RecentsAssembly): the flat feed
 * collapses across every lane so a title is one row, the digest collapses inside each lane so a series
 * read and updated today appears under both, and the two single-lane modes never collapse.
 */

/** Yokai's caps, adopted whole: four rows a section, and one budget of nine across the two chapter ones. */
private const val DIGEST_SECTION_CAP = 4
private const val DIGEST_CHAPTER_ROWS_CAP = 9

/** History: the stream under one header per day. */
fun historyRows(items: List<RecentsItem>): List<RecentsRow> = withDateHeaders(items)

/**
 * Updates: the same day headers, and with [groupBySeries] on, a series' several chapters from one day
 * behind one row. Grouping is per day rather than per series, so a series updated on two days keeps a
 * row under each. [seriesKeys] carries the merge-group key where one is known, which is what makes a
 * series merged across sources collapse into one group instead of one per source.
 */
fun updatesRows(
    items: List<RecentsItem>,
    groupBySeries: Boolean,
    seriesKeys: Map<EntryId, String>,
    expandedKeys: Set<String>,
): List<RecentsRow> {
    if (!groupBySeries) return withDateHeaders(items)
    val result = ArrayList<RecentsRow>(items.size + 8)
    byDay(items).forEach { (date, dayItems) ->
        result += RecentsRow.DateHeader(date)
        dayItems.groupByTo(LinkedHashMap()) { seriesKey(it, seriesKeys) }.forEach { (key, members) ->
            if (members.size < 2) {
                result += RecentsRow.Entry(members.first())
            } else {
                val groupKey = "$key@$date"
                val expanded = groupKey in expandedKeys
                result += RecentsRow.Group(groupKey, date, members, expanded)
                if (expanded) members.forEach { result += RecentsRow.Child(it) }
            }
        }
    }
    return result
}

/**
 * The flat combined feed: one row per title, newest activity first, no headers. A day header here would
 * make it History again, and the mode exists to answer a different question.
 */
fun flatRecentsRows(items: List<RecentsItem>): List<RecentsRow> =
    collapseByEntry(items).map(RecentsRow::Entry)

/**
 * The digest: each lane capped under its own header, sections ordered by whichever has the newest row.
 * The two chapter sections share one budget of nine, so a big update burst cannot push continue-reading
 * off the screen. Deliberately without Yokai's twelve-hour upload tiebreak inside new chapters: novel
 * updates carry no upload date at all, so it would order one content type's rows by a clock the other
 * does not have, invisibly. Record: content-layer-recents-surface.md.
 */
fun digestRows(items: List<RecentsItem>): List<RecentsRow> {
    val byLane = items.groupBy { it.lane.kind }
    fun lane(kind: RecentsLaneKind) = collapseByEntry(byLane[kind].orEmpty())

    val updated = lane(RecentsLaneKind.UPDATED).take(DIGEST_SECTION_CAP)
    val read = lane(RecentsLaneKind.READ).take((DIGEST_CHAPTER_ROWS_CAP - updated.size).coerceAtLeast(0))
    val added = lane(RecentsLaneKind.ADDED).take(DIGEST_SECTION_CAP)

    return listOf(
        RecentsLaneKind.UPDATED to updated,
        RecentsLaneKind.READ to read,
        RecentsLaneKind.ADDED to added,
    )
        .filter { (_, rows) -> rows.isNotEmpty() }
        .sortedByDescending { (_, rows) -> rows.first().timestamp }
        .flatMap { (section, rows) -> digestSection(section, rows) }
}

private fun digestSection(section: RecentsLaneKind, rows: List<RecentsItem>): List<RecentsRow> =
    buildList {
        add(RecentsRow.SectionHeader(section))
        rows.forEach { add(RecentsRow.Entry(it)) }
        // Newly added has no single-lane mode to jump to, so it gets no footer. Yokai does the same.
        if (section != RecentsLaneKind.ADDED) add(RecentsRow.SectionFooter(section))
    }

private fun withDateHeaders(items: List<RecentsItem>): List<RecentsRow> {
    val result = ArrayList<RecentsRow>(items.size + 8)
    var lastDate: LocalDate? = null
    items.forEach { item ->
        val date = item.timestamp.toLocalDate()
        if (date != lastDate) {
            result += RecentsRow.DateHeader(date)
            lastDate = date
        }
        result += RecentsRow.Entry(item)
    }
    return result
}

/** Day buckets in the order the stream arrived, which is already newest first. */
private fun byDay(items: List<RecentsItem>): Map<LocalDate, List<RecentsItem>> =
    items.groupByTo(LinkedHashMap()) { it.timestamp.toLocalDate() }

/**
 * Prefixed by content type because the two engines resolve their merge-group keys independently, so the
 * same key string can name a manga group and a novel group without the two being related.
 */
private fun seriesKey(item: RecentsItem, seriesKeys: Map<EntryId, String>): String =
    "${item.entryId.contentType}-${seriesKeys[item.entryId] ?: item.entryId.rawId}"
