package reikai.presentation.library

import reikai.domain.entry.EntryId
import reikai.presentation.library.ReikaiDynamicCategory.LANG_SPLITTER
import reikai.presentation.library.ReikaiDynamicCategory.SOURCE_SPLITTER
import tachiyomi.domain.category.model.Category

/**
 * Minimal per-item view the dynamic grouping needs, decoupled from the manga / novel domain types so
 * one kernel serves both libraries. [id] is generic because a row id is only unique WITHIN a content
 * type: a manga and a novel can share the id 12. A single-type caller buckets by its own `Long` row
 * id; a mixed caller buckets by the neutral [EntryId][reikai.domain.entry.EntryId]. Keying a mixed
 * call by `Long` would silently merge the two rows and cross-read one's metadata for the other, since
 * every metadata map below is keyed by this id.
 */
data class DynItem<K>(
    val id: K,
    val genre: List<String>?,
    val author: String?,
    val artist: String?,
)

/**
 * One provider's pre-resolved inputs for [LibraryDynamicGrouping.build], keyed by the neutral
 * [EntryId][reikai.domain.entry.EntryId] exactly as the kernel's own KDoc prescribes for a mixed call:
 * raw row ids collide across content types, and every map below is id-keyed. Each provider resolves its
 * own metadata (different source managers, different track tables); the engine concatenates the active
 * feeds and runs the kernel once, so the under-All merge and the group ordering come from the kernel's
 * existing logic rather than a hand-written list merge.
 */
class DynamicGroupingFeed(
    val items: List<DynItem<EntryId>>,
    val sourceMeta: Map<EntryId, Pair<String, String>> = emptyMap(),
    val languageCodes: Map<EntryId, String> = emptyMap(),
    val statusNames: Map<EntryId, String> = emptyMap(),
    val trackStatuses: Map<EntryId, String> = emptyMap(),
)

/**
 * Buckets library items into synthetic categories for dynamic grouping: by source, language, tag,
 * author, status or tracking status. Generalized over [DynItem] so the manga and novel libraries share
 * one kernel. Pure: every lookup needing a SourceManager, tracker or status is pre-resolved by the
 * caller and passed in as maps keyed by item id. Synthetic categories get NEGATIVE ids so they never
 * collide with real ones, carry [inheritedSortFlag] in [Category.flags], and encode their metadata in
 * [Category.name]; decode with [ReikaiDynamicCategory]. BY_DEFAULT returns empty.
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
        // buckets (multiple tags / authors); distinct guards against the same bucket twice.
        //
        // Keyed by [bucketKey] rather than the name, with the first spelling seen winning the display
        // name: sources spell one tag several ways ("Adult" against "ADULT"), and an exact-string key
        // renders those as two adjacent groups. Normalizing the display name would mangle acronyms
        // (BL, NTR). Callers concatenate the manga feed first, which keeps the choice stable.
        val bucketsByName = LinkedHashMap<String, MutableList<K>>()
        val displayNames = LinkedHashMap<String, String>()
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
            for (name in names.distinctBy { it.bucketKey() }) {
                val key = name.bucketKey()
                displayNames.getOrPut(key) { name }
                bucketsByName.getOrPut(key) { mutableListOf() }.add(item.id)
            }
        }

        // Step 2: synthetic Category per bucket. Negative id; sort inherited via flags.
        val categories = displayNames.values.mapIndexed { idx, encodedName ->
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

        // Step 4: optionally push collapsed groups to the bottom. Collapse keys compare by
        // normalized form (see ReikaiDynamicCategory.normalizeKey): the display name is the first
        // spelling seen, which changes when a filter or removal reorders the feed, so a raw-name
        // comparison silently expanded a merged group.
        val finalCategories = if (collapsedDynamicAtBottom) {
            val collapsedKeys =
                collapsedDynamicCategories.mapTo(HashSet(), ReikaiDynamicCategory::normalizeKey)
            sorted.filterNot { it.name.bucketKey() in collapsedKeys } +
                sorted.filter { it.name.bucketKey() in collapsedKeys }
        } else {
            sorted
        }

        return finalCategories.associateWith { bucketsByName[it.name.bucketKey()].orEmpty().toList() }
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

    /**
     * The merge key for a bucket name: case-folded, with hyphen / underscore / whitespace runs unified so
     * one tag spelled two ways by two sources is one group. Display keeps the first spelling seen. One
     * implementation with the collapse key, so a collapsed merged group stays matched to its bucket.
     */
    private fun String.bucketKey(): String = ReikaiDynamicCategory.normalizeKey(this)
}
