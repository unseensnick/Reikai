package reikai.presentation.library

import mihon.domain.library.model.search.AndNode
import mihon.domain.library.model.search.ComparisonField
import mihon.domain.library.model.search.ComparisonQueryNode
import mihon.domain.library.model.search.EmptyQueryNode
import mihon.domain.library.model.search.FieldQueryNode
import mihon.domain.library.model.search.GeneralQueryNode
import mihon.domain.library.model.search.MangaField
import mihon.domain.library.model.search.NotNode
import mihon.domain.library.model.search.OrNode
import mihon.domain.library.model.search.QueryNode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Per-entry accessors [libraryQueryMatches] reads, so the search never depends on the concrete row type.
 * The twin of [LibraryFilterFields]: each library supplies getters over its own row, and the grammar
 * (Mihon's lexer / parser / AST) stays upstream and unpatched, so one typed query means one thing on
 * every row of the mixed list.
 *
 * **A null return means the content type cannot answer that field at all**, which is not the same as an
 * absent value. An inapplicable field makes the whole term false BEFORE negation, so neither `nu<x` nor
 * `-nu<x` pulls those rows in. Upstream's own convention (absent, then negate, so `-author:x` keeps a
 * row with no author) still applies to fields the type *can* answer but this row leaves empty.
 *
 * [sourceKey] is the source's own identity, a numeric id for manga and a plugin slug for novels, which
 * is why it is a String on both sides.
 */
class LibraryQueryFields<T>(
    val id: (T) -> Long,
    val title: (T) -> String,
    val author: (T) -> String?,
    val artist: (T) -> String?,
    val description: (T) -> String?,
    val notes: (T) -> String?,
    val genre: (T) -> List<String>?,
    val sourceName: (T) -> String,
    /** The source's own identity: the numeric id for manga, the plugin slug for novels, hence String. */
    val sourceKey: (T) -> String,
    val sourceLanguage: (T) -> String?,
    /** Backs `source:local`. Always false for novels, which have no local-source concept. */
    val isLocal: (T) -> Boolean,
    val unreadCount: (T) -> Long,
    val readCount: (T) -> Long,
    val totalChapters: (T) -> Long,
    val dateAdded: (T) -> Long,
    /** Null for a content type with no fetch-interval concept (novels). */
    val fetchInterval: (T) -> Int?,
    /** Null for a content type with no next-update estimate (novels). */
    val nextUpdate: (T) -> Long?,
)

/** Whether [row] satisfies the parsed [node]. Pure over the [fields] accessors. */
fun <T> libraryQueryMatches(
    node: QueryNode,
    row: T,
    fields: LibraryQueryFields<T>,
): Boolean = when (node) {
    is AndNode -> node.children.all { libraryQueryMatches(it, row, fields) }
    is OrNode -> node.children.any { libraryQueryMatches(it, row, fields) }
    is NotNode -> !libraryQueryMatches(node.child, row, fields)
    is EmptyQueryNode -> true
    is GeneralQueryNode -> node.matches(row, fields)
    is FieldQueryNode -> node.matches(row, fields)
    is ComparisonQueryNode -> node.matches(row, fields)
}

/**
 * A bare word sweeps every field that is not [MangaField.fieldOnly], matching if any of them contains it.
 * The `when` is exhaustive on purpose: a field added upstream has to be answered here rather than
 * silently dropping out of bare-word search.
 */
private fun <T> GeneralQueryNode.matches(row: T, fields: LibraryQueryFields<T>): Boolean {
    val match = MangaField.entries.any { field ->
        if (field.fieldOnly) return@any false

        when (field) {
            MangaField.TITLE -> fields.title(row).contains(value, ignoreCase = true)
            MangaField.AUTHOR -> fields.author(row)?.contains(value, ignoreCase = true) ?: false
            MangaField.ARTIST -> fields.artist(row)?.contains(value, ignoreCase = true) ?: false
            MangaField.DESCRIPTION -> fields.description(row)?.contains(value, ignoreCase = true) ?: false
            MangaField.GENRE -> fields.genre(row)?.any { it.contains(value, ignoreCase = true) } ?: false
            MangaField.SOURCE -> matchesSource(row, fields, value)
            MangaField.NOTES -> fields.notes(row)?.contains(value, ignoreCase = true) ?: false

            // field-only; unreachable above, listed to keep the `when` exhaustive
            MangaField.LANGUAGE, MangaField.SOURCE_ID -> false
        }
    }
    return if (negated) !match else match
}

private fun <T> FieldQueryNode.matches(row: T, fields: LibraryQueryFields<T>): Boolean {
    val match = when (field) {
        MangaField.GENRE -> {
            val genre = fields.genre(row)
            if (value.isEmpty()) genre.isNullOrEmpty() else genre?.any { it.contains(value, true) } ?: false
        }

        MangaField.SOURCE -> {
            if (value.isEmpty()) fields.sourceName(row).isEmpty() else matchesSource(row, fields, value)
        }

        MangaField.SOURCE_ID -> fields.sourceKey(row).equals(value, ignoreCase = true)

        else -> {
            val text = when (field) {
                MangaField.TITLE -> fields.title(row)
                MangaField.AUTHOR -> fields.author(row)
                MangaField.ARTIST -> fields.artist(row)
                MangaField.DESCRIPTION -> fields.description(row)
                MangaField.NOTES -> fields.notes(row)
                MangaField.LANGUAGE -> fields.sourceLanguage(row)

                // unreachable; listed to keep the `when` exhaustive
                MangaField.GENRE, MangaField.SOURCE, MangaField.SOURCE_ID -> null
            }
            if (value.isEmpty()) text.isNullOrEmpty() else text?.contains(value, ignoreCase = true) ?: false
        }
    }
    return if (negated) !match else match
}

/**
 * `source:` / `src:` match the source's display name, upstream's meaning unchanged, plus the `local`
 * keyword. The exact-key form is `srcid:`, which answers identically on both content types: a numeric
 * source id for manga, a plugin slug for novels.
 */
private fun <T> matchesSource(row: T, fields: LibraryQueryFields<T>, value: String): Boolean =
    fields.sourceName(row).contains(value, ignoreCase = true) ||
        (value.equals("local", ignoreCase = true) && fields.isLocal(row))

private fun <T> ComparisonQueryNode.matches(row: T, fields: LibraryQueryFields<T>): Boolean {
    fun compareDates(timestamp: Long): Boolean? {
        val inputDate = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
        val rowDate = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        return queryComparator.apply(rowDate, inputDate)
    }

    // Split from the value comparison below: a field this content type cannot answer is false whether or
    // not the term is negated, so an inapplicable comparison never pulls the row in from either side.
    val applicable = when (field) {
        ComparisonField.FETCH_INTERVAL -> fields.fetchInterval(row) != null
        ComparisonField.NEXT_UPDATE -> fields.nextUpdate(row) != null
        else -> true
    }
    if (!applicable) return false

    val match = when (field) {
        ComparisonField.ID -> value.toLongOrNull()?.let { queryComparator.apply(fields.id(row), it) }
        ComparisonField.DATE_ADDED -> compareDates(fields.dateAdded(row))
        ComparisonField.FETCH_INTERVAL -> value.toIntOrNull()
            ?.let { queryComparator.apply(abs(fields.fetchInterval(row)!!), it) }
        ComparisonField.NEXT_UPDATE -> compareDates(fields.nextUpdate(row)!!)
        ComparisonField.UNREAD -> value.toLongOrNull()?.let { queryComparator.apply(fields.unreadCount(row), it) }
        ComparisonField.READ -> value.toLongOrNull()?.let { queryComparator.apply(fields.readCount(row), it) }
        ComparisonField.TOTAL -> value.toLongOrNull()?.let { queryComparator.apply(fields.totalChapters(row), it) }
    } ?: false

    return if (negated) !match else match
}
