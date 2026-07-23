package reikai.presentation.library

import reikai.presentation.library.ReikaiDynamicCategory.LANG_SPLITTER
import reikai.presentation.library.ReikaiDynamicCategory.SOURCE_SPLITTER
import tachiyomi.domain.category.model.Category

/**
 * Minimal per-item view the dynamic grouping needs, decoupled from the manga / novel domain types
 * so one kernel serves both libraries. The caller maps its favorites (LibraryManga or LibraryNovel)
 * into these.
 *
 * [id] is generic because a row id is only unique WITHIN one content type: a manga and a novel can
 * share the id 12. A single-content-type caller buckets by its own `Long` row id; a mixed
 * manga-plus-novel caller buckets by the neutral [EntryId][reikai.domain.entry.EntryId]. Keying a
 * mixed call by `Long` would silently merge the two rows (the kernel de-duplicates by id) and would
 * cross-read one's metadata for the other, since every metadata map below is keyed by this id.
 */
data class DynItem<K>(
    val id: K,
    val genre: List<String>?,
    val author: String?,
    val artist: String?,
)

/**
 * Buckets library items into synthetic categories for dynamic grouping: group by source,
 * language, tag, author, status, or tracking status. Re-typed onto Mihon's immutable models from
 * the Yōkai-era `MangaLibraryDynamicGrouping`, and generalized over [DynItem] so the manga and novel
 * libraries share one kernel.
 *
 * Pure function: all per-item metadata that needs a SourceManager / tracker / status lookup is
 * pre-resolved by the caller and passed in as maps keyed by item id, so this stays unit-testable.
 *
 * Synthetic categories get **negative ids** (so they never collide with real DB categories) and
 * carry [inheritedSortFlag] in [Category.flags] (so the caller's sort orders their items).
 * Metadata is encoded into [Category.name]; decode with [ReikaiDynamicCategory].
 *
 * BY_DEFAULT (and any non-dynamic type) returns empty; the caller routes those through its own
 * category bucketing.
 */
object LibraryDynamicGrouping {

    private val DYNAMIC_GROUP_TYPES = setOf(
        LibraryGroup.BY_TAG,
        LibraryGroup.BY_SOURCE,
        LibraryGroup.BY_LANGUAGE,
        LibraryGroup.BY_AUTHOR,
        LibraryGroup.BY_STATUS,
        LibraryGroup.BY_TRACK_STATUS,
    )

    @Suppress("LongParameterList")
    fun <K> build(
        items: List<DynItem<K>>,
        groupType: Int,
        inheritedSortFlag: Long,
        collapsedDynamicCategories: Set<String>,
        collapsedDynamicAtBottom: Boolean,
        unknownLabel: String,
        notTrackedLabel: String,
        ungroupedLabel: String = "",
        categorySortOrder: Int = 0,
        sourceMeta: Map<K, Pair<String, String>> = emptyMap(),
        trackStatuses: Map<K, String> = emptyMap(),
        languageCodes: Map<K, String> = emptyMap(),
        statusNames: Map<K, String> = emptyMap(),
        languageDisplay: (langCode: String) -> String = { it },
        trackingStatusOrder: (statusName: String) -> String = { it },
    ): Map<Category, List<K>> {
        if (items.isEmpty()) return emptyMap()

        // UNGROUPED: one flat synthetic bucket holding every item, no per-item metadata lookups.
        if (groupType == LibraryGroup.UNGROUPED) {
            val allIds = items.distinctBy { it.id }.map { it.id }
            val category = Category(id = -1L, name = ungroupedLabel, order = 0L, flags = inheritedSortFlag)
            return mapOf(category to allIds)
        }

        if (groupType !in DYNAMIC_GROUP_TYPES) return emptyMap()

        val deduplicated = items.distinctBy { it.id }

        // Step 1: per-item, the encoded bucket name(s) it belongs to. An item can land in several
        // buckets (multiple tags / authors); distinct() guards against the same bucket twice.
        val bucketsByName = LinkedHashMap<String, MutableList<K>>()
        for (item in deduplicated) {
            val names = categoryNamesFor(
                item = item,
                groupType = groupType,
                unknownLabel = unknownLabel,
                notTrackedLabel = notTrackedLabel,
                sourceMeta = sourceMeta,
                trackStatuses = trackStatuses,
                languageCodes = languageCodes,
                statusNames = statusNames,
                languageDisplay = languageDisplay,
            )
            for (name in names.distinct()) {
                bucketsByName.getOrPut(name) { mutableListOf() }.add(item.id)
            }
        }

        // Step 2: synthetic Category per bucket. Negative id; sort inherited via flags.
        val categories = bucketsByName.keys.mapIndexed { idx, encodedName ->
            Category(
                id = -(idx + 1).toLong(),
                name = encodedName,
                order = idx.toLong(),
                flags = inheritedSortFlag,
            )
        }

        // Step 3: order the category buckets. Tracking status has an inherent reading-progress order
        // (Reading first, Not tracked last), so it always uses that and ignores the alphabetical category
        // sort, which is meant for name-keyed groupings (source / tag / author / language). For those,
        // Z->A reverses and off / A->Z is alphabetical by display name.
        val sorted = if (groupType == LibraryGroup.BY_TRACK_STATUS) {
            categories.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { trackingStatusOrder(ReikaiDynamicCategory.displayName(it)) },
            )
        } else if (categorySortOrder == 2) {
            categories.sortedByDescending { ReikaiDynamicCategory.displayName(it).lowercase() }
        } else {
            categories.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { ReikaiDynamicCategory.displayName(it) },
            )
        }

        // Step 4: optionally push collapsed groups to the bottom.
        val finalCategories = if (collapsedDynamicAtBottom) {
            sorted.filterNot { it.name in collapsedDynamicCategories } +
                sorted.filter { it.name in collapsedDynamicCategories }
        } else {
            sorted
        }

        return finalCategories.associateWith { bucketsByName[it.name].orEmpty().toList() }
    }

    private fun <K> categoryNamesFor(
        item: DynItem<K>,
        groupType: Int,
        unknownLabel: String,
        notTrackedLabel: String,
        sourceMeta: Map<K, Pair<String, String>>,
        trackStatuses: Map<K, String>,
        languageCodes: Map<K, String>,
        statusNames: Map<K, String>,
        languageDisplay: (langCode: String) -> String,
    ): List<String> {
        val itemId = item.id
        return when (groupType) {
            LibraryGroup.BY_TAG -> {
                val tags = item.genre.orEmpty().mapNotNull { it.trim().capitalizeWords().ifBlank { null } }
                tags.ifEmpty { listOf(unknownLabel) }
            }
            LibraryGroup.BY_SOURCE -> {
                val meta = sourceMeta[itemId] ?: return listOf("$unknownLabel${SOURCE_SPLITTER}0")
                listOf("${meta.first}$SOURCE_SPLITTER${meta.second}")
            }
            LibraryGroup.BY_LANGUAGE -> {
                val code = languageCodes[itemId]
                if (code.isNullOrBlank()) {
                    listOf(unknownLabel)
                } else {
                    listOf("$code$LANG_SPLITTER${languageDisplay(code).ifBlank { code }}")
                }
            }
            LibraryGroup.BY_AUTHOR -> {
                val author = item.author?.takeUnless { it.isBlank() }
                val artist = item.artist?.takeUnless { it.isBlank() }
                if (author == null && artist == null) {
                    listOf(unknownLabel)
                } else {
                    listOfNotNull(author, artist)
                        .flatMap { combined ->
                            combined.split(",", "/", " x ", " - ", ignoreCase = true)
                                .mapNotNull { it.trim().ifBlank { null } }
                        }
                        .distinct()
                        .ifEmpty { listOf(unknownLabel) }
                }
            }
            LibraryGroup.BY_TRACK_STATUS -> listOf(trackStatuses[itemId] ?: notTrackedLabel)
            LibraryGroup.BY_STATUS -> listOf(statusNames[itemId] ?: unknownLabel)
            else -> emptyList()
        }
    }

    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
}
