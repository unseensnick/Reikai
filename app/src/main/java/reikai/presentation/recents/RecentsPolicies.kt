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

/**
 * The rows one mode draws, which is where the mode picks its policy and its share of the stream. The
 * engine collects every lane the surface renders, so on a surface holding several modes the assembly
 * carries rows this mode does not show: a single-lane mode would otherwise draw the other lanes'.
 * [keep] is the chapter-state filter, which needs a row's own projection and so is asked rather than
 * applied here; it runs before the collapse, so a hidden row cannot stand in for a series.
 */
fun renderRows(
    mode: RecentsMode,
    assembled: RecentsAssembled,
    groupBySeries: Boolean,
    expandedGroups: Set<String>,
    keep: (RecentsItem) -> Boolean = { true },
): List<RecentsRow> {
    val items = assembled.items.filter { it.lane.kind in mode.lanes && keep(it) }
    val membership = assembled.membership
    return when (mode) {
        RecentsMode.UPDATES -> updatesRows(items, groupBySeries, membership, expandedGroups)
        RecentsMode.HISTORY -> historyRows(items, membership)
        RecentsMode.FEED -> flatRecentsRows(items, membership)
        RecentsMode.DIGEST -> digestRows(items, membership)
    }
}

/**
 * History: the stream under one header per day. Already one row per entry when it arrives (the feed
 * queries collapse on last-read), so the collapse here is only about merge groups, which SQL cannot
 * see: two sources of one series are two entries, and reading either is reading the same book.
 */
fun historyRows(items: List<RecentsItem>, membership: Map<EntryId, Long>): List<RecentsRow> =
    withDateHeaders(collapseByGroup(items, membership))

/**
 * Updates: the same day headers, and with [groupBySeries] on, a series' several chapters from one day
 * behind one row. Grouping is per day rather than per series, so a series updated on two days keeps a
 * row under each. [seriesKeys] carries the merge-group key where one is known, which is what makes a
 * series merged across sources collapse into one group instead of one per source.
 */
fun updatesRows(
    items: List<RecentsItem>,
    groupBySeries: Boolean,
    membership: Map<EntryId, Long>,
    expandedKeys: Set<String>,
): List<RecentsRow> {
    if (!groupBySeries) return withDateHeaders(items)
    val result = ArrayList<RecentsRow>(items.size + 8)
    byDay(items).forEach { (date, dayItems) ->
        result += RecentsRow.DateHeader(date)
        dayItems.groupByTo(LinkedHashMap()) { seriesKey(it, membership) }.forEach { (key, members) ->
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
 * make it History again, and the mode exists to answer a different question. A series merged across
 * sources is one title, so the collapse runs over [membership].
 */
fun flatRecentsRows(items: List<RecentsItem>, membership: Map<EntryId, Long>): List<RecentsRow> =
    collapseByGroup(items, membership).map(RecentsRow::Entry)

/**
 * The digest: each lane capped under its own header, sections ordered by whichever has the newest row.
 * The two chapter sections share one budget of nine, so a big update burst cannot push continue-reading
 * off the screen. Deliberately without Yokai's twelve-hour upload tiebreak inside new chapters: novel
 * updates carry no upload date at all, so it would order one content type's rows by a clock the other
 * does not have, invisibly. Record: content-layer-recents-surface.md.
 */
fun digestRows(items: List<RecentsItem>, membership: Map<EntryId, Long>): List<RecentsRow> {
    val byLane = items.groupBy { it.lane.kind }
    fun lane(kind: RecentsLaneKind) = collapseByGroup(byLane[kind].orEmpty(), membership)

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
 * A group id where the entry is in one, else the entry itself. Group ids are unique across both content
 * types, but raw entry ids are not, so the standalone half carries the content type.
 */
private fun seriesKey(item: RecentsItem, membership: Map<EntryId, Long>): String =
    membership[item.entryId]?.let { "g$it" } ?: "${item.entryId.contentType}-${item.entryId.rawId}"
