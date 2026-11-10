package reikai.domain.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.manga.model.applyFilter

/**
 * The library's tag filter, for both content types: tags a series must carry ([include], all of them when
 * [includeAll], else any) and tags it must not ([exclude], dropped when it has all of them with [excludeAll],
 * else any), plus [noTags] for series without a tag. Tags are stored and compared as [tagKey]s.
 */
data class TagFilter(
    val include: Set<String> = emptySet(),
    val exclude: Set<String> = emptySet(),
    val includeAll: Boolean = false,
    val excludeAll: Boolean = false,
    val noTags: TriState = TriState.DISABLED,
) {
    val isActive: Boolean get() = include.isNotEmpty() || exclude.isNotEmpty() || noTags != TriState.DISABLED

    /** Whether a series showing [tags] passes. */
    fun matches(tags: Collection<String>): Boolean = matchesKeys(tagKeys(tags))

    /** [matches] over a series' [tagKeys], for a caller testing one series against many filters. */
    fun matchesKeys(keys: Set<String>): Boolean {
        if (!applyFilter(noTags) { keys.isEmpty() }) return false
        val excluded = exclude.isNotEmpty() && if (excludeAll) keys.containsAll(exclude) else exclude.any { it in keys }
        if (excluded) return false
        return include.isEmpty() || if (includeAll) keys.containsAll(include) else include.any { it in keys }
    }
}

/** The stored tag filter, read by both libraries' filters and by everything that edits it. */
fun ReikaiLibraryPreferences.tagFilterFlow(): Flow<TagFilter> = combine(
    filterTagsInclude.changes(),
    filterTagsExclude.changes(),
    filterTagsIncludeAll.changes(),
    filterTagsExcludeAll.changes(),
    filterNoTags.changes(),
    ::TagFilter,
)

/** The stored tag filter as it is now, for a reader that cannot wait on [tagFilterFlow]. */
fun ReikaiLibraryPreferences.currentTagFilter() = TagFilter(
    filterTagsInclude.get(),
    filterTagsExclude.get(),
    filterTagsIncludeAll.get(),
    filterTagsExcludeAll.get(),
    filterNoTags.get(),
)

/** One tag's three states in the picker, which a tap cycles through: not filtered, required, then hidden. */
fun ReikaiLibraryPreferences.cycleTag(key: String) = when (key) {
    in filterTagsInclude.get() -> hideTag(key)
    in filterTagsExclude.get() -> clearTag(key)
    else -> filterTagsInclude.set(filterTagsInclude.get() + key)
}

/** Shows only series carrying [key], replacing the other required tags. */
fun ReikaiLibraryPreferences.showOnlyTag(key: String) {
    filterTagsInclude.set(setOf(key))
    filterTagsExclude.set(filterTagsExclude.get() - key)
    // Only untagged series would leave nothing to show.
    if (filterNoTags.get() == TriState.ENABLED_IS) filterNoTags.set(TriState.DISABLED)
}

fun ReikaiLibraryPreferences.hideTag(key: String) {
    filterTagsInclude.set(filterTagsInclude.get() - key)
    filterTagsExclude.set(filterTagsExclude.get() + key)
}

fun ReikaiLibraryPreferences.clearTag(key: String) {
    filterTagsInclude.set(filterTagsInclude.get() - key)
    filterTagsExclude.set(filterTagsExclude.get() - key)
}

fun ReikaiLibraryPreferences.clearTagFilter() {
    filterTagsInclude.set(emptySet())
    filterTagsExclude.set(emptySet())
    filterNoTags.set(TriState.DISABLED)
}

/** A series' tags as [TagFilter.matchesKeys] reads them, blank ones dropped. */
fun tagKeys(tags: Collection<String>): Set<String> = tags.mapNotNullTo(HashSet()) { tagKey(it).ifEmpty { null } }

/** A tag as the library compares it: case, and runs of dashes, underscores and spaces, folded. */
fun tagKey(tag: String): String = tag.lowercase().replace(TAG_SEPARATORS, " ").trim()

private val TAG_SEPARATORS = Regex("[-_\\s]+")

/** A tag as the picker shows it: a source's ALL CAPS spelling is shown capitalised, "ACTION" as "Action". */
private fun tagLabel(tag: String): String {
    val trimmed = tag.trim()
    // A short one is likely an acronym (BL, GL), which capitalising would garble.
    if (trimmed.length <= 3 || trimmed != trimmed.uppercase() || trimmed.none(Char::isLetter)) return trimmed
    return trimmed.lowercase().replaceFirstChar(Char::uppercaseChar)
}

/** One tag the picker offers: its [key], the first spelling the library shows it in, and how many series carry it. */
data class TagChoice(val key: String, val label: String, val count: Int)

/** Every tag across [series] (each one series' tags), most common first, then by name. */
fun tagChoices(series: List<Collection<String>>): List<TagChoice> {
    val labels = LinkedHashMap<String, String>()
    val counts = HashMap<String, Int>()
    series.forEach { tags ->
        tags.mapNotNullTo(HashSet()) { tag ->
            tagKey(tag).ifEmpty { null }?.also { labels.putIfAbsent(it, tagLabel(tag)) }
        }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
    }
    return labels.map { (key, label) -> TagChoice(key, label, counts.getValue(key)) }
        .sortedWith(compareByDescending<TagChoice> { it.count }.thenBy { it.label.lowercase() })
}
