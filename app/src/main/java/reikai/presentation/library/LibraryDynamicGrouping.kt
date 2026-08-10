package reikai.presentation.library

import reikai.domain.entry.EntryId

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

private val SEPARATOR_RUN = Regex("[-_\\s]+")

/**
 * Normalized form of a dynamic bucket's key, matching the spelling-merge rule below (case-folded,
 * separator runs unified). This is what [LibraryBucket.Dynamic.key] holds and what is persisted in
 * `collapsed_dynamic_categories`. Stored keys may predate normalization, so membership checks
 * normalize both sides.
 */
fun normalizeDynamicKey(name: String): String = name.lowercase().replace(SEPARATOR_RUN, " ").trim()

/**
 * Buckets library items into synthetic groups: by source, language, tag, author, status or tracking
 * status. Generalized over [DynItem] so both libraries share one kernel. Pure: anything needing a
 * SourceManager, tracker or status is pre-resolved by the caller. BY_DEFAULT returns empty.
 * Source and language buckets encode a disambiguator into the key (two sources can share a name, a
 * language code is not its label), which is why key and label are separate. That encoding is
 * persisted, so the splitters match the Yokai-era fork's for upgrade continuity.
 */
object LibraryDynamicGrouping {

    private const val SOURCE_SPLITTER = "◘•◘"
    private const val LANG_SPLITTER = "⨼⨦⨠"

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
    ): Map<LibraryBucket.Dynamic, List<K>> {
        if (items.isEmpty()) return emptyMap()

        // UNGROUPED: one flat synthetic bucket holding every item, no per-item metadata lookups.
        if (groupType == LibraryGroup.UNGROUPED) {
            val allIds = items.distinctBy { it.id }.map { it.id }
            val bucket = LibraryBucket.Dynamic(normalizeDynamicKey(ungroupedLabel), ungroupedLabel)
            return mapOf(bucket to allIds)
        }

        if (groupType !in DYNAMIC_GROUP_TYPES) return emptyMap()

        val deduplicated = items.distinctBy { it.id }

        // Step 1: per-item, the encoded bucket name(s) it belongs to. An item can land in several
        // buckets (multiple tags / authors); distinct guards against the same bucket twice.
        //
        // Keyed by the normalized name, with the first spelling seen winning the label: sources spell
        // one tag several ways ("Adult" against "ADULT"), and an exact-string key renders those as two
        // adjacent groups. Normalizing the label would mangle acronyms (BL, NTR). Callers concatenate
        // the manga feed first, which keeps the choice stable.
        val idsByKey = LinkedHashMap<String, MutableList<K>>()
        val encodedByKey = LinkedHashMap<String, String>()
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
            for (name in names.distinctBy(::normalizeDynamicKey)) {
                val key = normalizeDynamicKey(name)
                encodedByKey.getOrPut(key) { name }
                idsByKey.getOrPut(key) { mutableListOf() }.add(item.id)
            }
        }

        // Step 2: one bucket per distinct key, labelled with the first spelling seen.
        val buckets = encodedByKey.map { (key, encoded) -> LibraryBucket.Dynamic(key, labelOf(encoded)) }

        // Step 3: order the buckets. Tracking status has an inherent reading-progress order (Reading
        // first, Not tracked last), so it always uses that and ignores the alphabetical category sort,
        // which is meant for name-keyed groupings (source / tag / author / language). For those, Z->A
        // reverses and off / A->Z is alphabetical by label.
        val sorted = if (groupType == LibraryGroup.BY_TRACK_STATUS) {
            buckets.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { trackingStatusOrder(it.label) })
        } else if (categorySortOrder == 2) {
            buckets.sortedByDescending { it.label.lowercase() }
        } else {
            buckets.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        }

        // Step 4: optionally push collapsed groups to the bottom. Stored keys may predate normalization,
        // so normalize that side too.
        val finalBuckets = if (collapsedDynamicAtBottom) {
            val collapsedKeys = collapsedDynamicCategories.mapTo(HashSet(), ::normalizeDynamicKey)
            sorted.filterNot { it.key in collapsedKeys } + sorted.filter { it.key in collapsedKeys }
        } else {
            sorted
        }

        return finalBuckets.associateWith { idsByKey[it.key].orEmpty().toList() }
    }

    /** The label behind an encoded name: the part that is not the source-id / language-code disambiguator. */
    private fun labelOf(encodedName: String): String = when {
        SOURCE_SPLITTER in encodedName -> encodedName.substringBefore(SOURCE_SPLITTER)
        LANG_SPLITTER in encodedName -> encodedName.substringAfter(LANG_SPLITTER)
        else -> encodedName
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
