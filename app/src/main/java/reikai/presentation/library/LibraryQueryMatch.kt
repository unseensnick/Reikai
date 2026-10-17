package reikai.presentation.library

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
import reikai.domain.category.isUncategorized
import reikai.presentation.components.EntryStatus
import tachiyomi.domain.category.model.Category
import kotlin.math.abs
import kotlin.time.Instant

/**
 * Per-entry accessors [libraryQueryMatches] reads, so search never depends on the concrete row type.
 * The search counterpart of [LibraryFilterFields]: each library supplies getters over its own row while the grammar
 * stays upstream and unpatched, so one typed query means one thing on every row of the mixed list.
 */
class LibraryQueryFields<T>(
    val id: (T) -> Long,
    val title: (T) -> String,
    val author: (T) -> String?,
    val artist: (T) -> String?,
    val description: (T) -> String?,
    val notes: (T) -> String?,
    val genre: (T) -> List<String>?,
    /**
     * Every source the entry reads from: one, or each member of a merged series. A source term matches
     * when any of them does, so a merged series is found from any of its sources, not just its lead.
     */
    val sources: (T) -> List<LibraryQuerySource>,
    val unreadCount: (T) -> Long,
    val readCount: (T) -> Long,
    val totalChapters: (T) -> Long,
    val dateAdded: (T) -> Instant?,
    val fetchInterval: (T) -> Int,
    val nextUpdate: (T) -> Instant?,
    /**
     * Whether [T] owns a chapter matching the term, answered from a set resolved once per query by
     * [chapterSearchTerms]. Null when the term was never resolved, which is a caller bug rather than a
     * no-match, so it reads as false rather than quietly keeping the row.
     */
    val matchesChapter: (T, String) -> Boolean?,
    /**
     * Whether a gallery row's tag grammar (namespace:tag, wildcards, exact, alt-titles) finds a word or
     * field term, printed back as typed. False for a row with no gallery metadata, novels included.
     */
    val matchesTagTerm: (T, String) -> Boolean,
    /** The publishing status code, the source's own as the filters read it. */
    val status: (T) -> Long,
    /** Names of the logged-in trackers bound on the entry or any merged member. */
    val trackerNames: (T) -> List<String>,
    val categoryIds: (T) -> List<Long>,
    /** Whether the entry is in a category the term names, resolved once per query like [matchesChapter]. */
    val matchesCategory: (T, String) -> Boolean?,
    /** The mean 0 to 10 tracker score, the one the library sorts by; null when unscored. */
    val meanScore: (T) -> Double?,
)

/**
 * A row's custom-info overrides, neutral over the two content types' own custom-info rows, so search can
 * match the values actually on the card. A null field means no override, so the source value is used.
 * `status` is overridable too, but `status:` reads the source's value as the filters do, so it is not here.
 */
class LibraryQueryOverlay(
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
)

/** What the source terms (`source:`, `srcid:`, `lang:`) read about one of an entry's sources. */
data class LibraryQuerySource(
    /** The numeric id for manga; for novels a plugin slug, or `tachiyomi:<id>` / `ireader:<id>`. */
    val key: String,
    val name: String,
    val language: String?,
    /** Backs `source:local`. Always false for novels, which have no local-source concept. */
    val isLocal: Boolean,
)

/**
 * Every `chapter:` term in a parsed query, so a caller resolves exactly the lookups the user typed and
 * nothing else. Walks the tree rather than the raw string, so quoting, negation and nesting are already
 * handled: `-chapter:"the clown"` yields `the clown`.
 */
fun QueryNode.chapterSearchTerms(): Set<String> = fieldTerms(MangaField.CHAPTER)

/** Every `category:` term, resolved to category ids once per query as [chapterSearchTerms] are. */
fun QueryNode.categorySearchTerms(): Set<String> = fieldTerms(MangaField.CATEGORY)

/**
 * Each `category:` term to the ids of [categories] named exactly that, ignoring case, for
 * [LibraryQueryFields.matchesCategory]. Whole names, so a search for "Read" leaves out "Reading".
 */
fun QueryNode.categoryMatches(categories: List<Category>): Map<String, Set<Long>> =
    categorySearchTerms().associateWith { term ->
        categories.filter { it.name.equals(term, ignoreCase = true) }.mapTo(mutableSetOf()) { it.id }
    }

private fun QueryNode.fieldTerms(field: MangaField): Set<String> = when (this) {
    is AndNode -> children.flatMapTo(mutableSetOf()) { it.fieldTerms(field) }
    is OrNode -> children.flatMapTo(mutableSetOf()) { it.fieldTerms(field) }
    is NotNode -> child.fieldTerms(field)
    is FieldQueryNode -> if (this.field == field && value.isNotEmpty()) setOf(value) else emptySet()
    else -> emptySet()
}

/** Whether a query reads trackers, so a library that loads tracks only on demand loads them for it. */
fun QueryNode.readsTracks(): Boolean = when (this) {
    is AndNode -> children.any { it.readsTracks() }
    is OrNode -> children.any { it.readsTracks() }
    is NotNode -> child.readsTracks()
    is FieldQueryNode -> field == MangaField.TRACKER
    is ComparisonQueryNode -> field == ComparisonField.SCORE
    else -> false
}

/** The word `status:` matches a publishing status by; both content types share the codes. */
fun statusQueryWord(code: Long): String = EntryStatus.of(code).queryWord

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
            MangaField.LANGUAGE, MangaField.SOURCE_ID, MangaField.CHAPTER,
            MangaField.STATUS, MangaField.TRACKER, MangaField.CATEGORY,
            -> false
        }
    } || fields.matchesTagTerm(row, termText(value))
    return if (negated) !match else match
}

private fun <T> FieldQueryNode.matches(row: T, fields: LibraryQueryFields<T>): Boolean {
    val match = when (field) {
        MangaField.GENRE -> {
            val genre = fields.genre(row)
            if (value.isEmpty()) genre.isNullOrEmpty() else genre?.any { it.contains(value, true) } ?: false
        }

        // An empty value asks for an absent one, which a merged series has only when every source lacks it.
        MangaField.SOURCE -> {
            if (value.isEmpty()) fields.sources(row).all { it.name.isEmpty() } else matchesSource(row, fields, value)
        }

        MangaField.SOURCE_ID -> fields.sources(row).any { it.key.equals(value, ignoreCase = true) }

        MangaField.LANGUAGE -> {
            val languages = fields.sources(row).map { it.language }
            if (value.isEmpty()) {
                languages.all { it.isNullOrEmpty() }
            } else {
                languages.any { it?.contains(value, ignoreCase = true) ?: false }
            }
        }

        MangaField.CHAPTER -> if (value.isEmpty()) false else fields.matchesChapter(row, value) ?: false

        // A status is a word or its code; an empty value asks for an unknown one.
        MangaField.STATUS -> {
            val code = fields.status(row)
            if (value.isEmpty()) {
                EntryStatus.of(code) == EntryStatus.UNKNOWN
            } else {
                statusQueryWord(code).startsWith(value, ignoreCase = true) || value == code.toString()
            }
        }

        MangaField.TRACKER -> {
            val names = fields.trackerNames(row)
            // Whole names, as category's are: "AniList" is not a part of another tracker's name, but stays exact.
            if (value.isEmpty()) names.isEmpty() else names.any { it.equals(value, ignoreCase = true) }
        }

        // An empty value asks for an entry filed in none of the user's categories, which reads as category 0.
        MangaField.CATEGORY -> if (value.isEmpty()) {
            fields.categoryIds(row).isUncategorized()
        } else {
            fields.matchesCategory(row, value) ?: false
        }

        else -> {
            val text = when (field) {
                MangaField.TITLE -> fields.title(row)
                MangaField.AUTHOR -> fields.author(row)
                MangaField.ARTIST -> fields.artist(row)
                MangaField.DESCRIPTION -> fields.description(row)
                MangaField.NOTES -> fields.notes(row)

                // unreachable; listed to keep the `when` exhaustive
                MangaField.GENRE, MangaField.SOURCE, MangaField.SOURCE_ID, MangaField.LANGUAGE,
                MangaField.CHAPTER, MangaField.STATUS, MangaField.TRACKER, MangaField.CATEGORY,
                -> null
            }
            if (value.isEmpty()) text.isNullOrEmpty() else text?.contains(value, ignoreCase = true) ?: false
        }
        // An empty value asks for an absent field, which the tag grammar has no reading of, and a field only
        // the library holds is never a gallery's tag: some sites file their own `category` namespace.
    } || (
        value.isNotEmpty() && field !in LIBRARY_ONLY_FIELDS &&
            fields.matchesTagTerm(row, "${field.aliases.first()}:${termText(value)}")
        )
    return if (negated) !match else match
}

private val LIBRARY_ONLY_FIELDS = setOf(MangaField.STATUS, MangaField.TRACKER, MangaField.CATEGORY)

// The tag grammar reads a term before its negation, so an excluded term drops a row either grammar finds
// it in. The lexer unquoted the value, so one the tag grammar would split or read as `-` is quoted back.
private fun termText(value: String): String =
    if (value.startsWith('-') || value.any { it.isWhitespace() || it == ',' }) "\"$value\"" else value

/**
 * The library query for one source's entries: its exact key, since two sources can share a name and a
 * language (an app and an IReader copy of one site). Quoted, so any key reads as one value.
 */
fun sourceKeyQuery(sourceKey: String): String = "srcid:\"$sourceKey\""

/**
 * [value] quoted for a `field:` term, in whichever quote it does not hold, since the lexer has no escape.
 * Null for a value holding both, which no query can name.
 */
fun quotedQueryValue(value: String): String? = when {
    '"' !in value -> "\"$value\""
    '\'' !in value -> "'$value'"
    else -> null
}

/**
 * `source:` / `src:` match the source's display name, upstream's meaning unchanged, plus the `local`
 * keyword. The exact-key form is `srcid:`, which answers identically on both content types; see
 * [LibraryQuerySource.key].
 */
private fun <T> matchesSource(row: T, fields: LibraryQueryFields<T>, value: String): Boolean =
    fields.sources(row).any { source ->
        source.name.contains(value, ignoreCase = true) || (value.equals("local", ignoreCase = true) && source.isLocal)
    }

private fun <T> ComparisonQueryNode.matches(row: T, fields: LibraryQueryFields<T>): Boolean {
    fun compareDates(date: Instant): Boolean? {
        val inputDate = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
        val rowDate = date.toLocalDateTime(TimeZone.currentSystemDefault()).date
        return queryComparator.apply(rowDate, inputDate)
    }

    val match = when (field) {
        ComparisonField.ID -> value.toLongOrNull()?.let { queryComparator.apply(fields.id(row), it) }
        ComparisonField.DATE_ADDED -> fields.dateAdded(row)?.let { compareDates(it) }
        ComparisonField.FETCH_INTERVAL -> value.toIntOrNull()
            ?.let { queryComparator.apply(abs(fields.fetchInterval(row)), it) }
        ComparisonField.NEXT_UPDATE -> fields.nextUpdate(row)?.let { compareDates(it) }
        ComparisonField.UNREAD -> value.toLongOrNull()?.let { queryComparator.apply(fields.unreadCount(row), it) }
        ComparisonField.READ -> value.toLongOrNull()?.let { queryComparator.apply(fields.readCount(row), it) }
        ComparisonField.TOTAL -> value.toLongOrNull()?.let { queryComparator.apply(fields.totalChapters(row), it) }
        ComparisonField.SCORE -> value.toDoubleOrNull()?.let { wanted ->
            fields.meanScore(row)?.let { queryComparator.apply(it, wanted) }
        }
    } ?: false

    return if (negated) !match else match
}
