package reikai.presentation.library

import reikai.presentation.library.ReikaiDynamicCategory.LANG_SPLITTER
import reikai.presentation.library.ReikaiDynamicCategory.SOURCE_SPLITTER
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort

/**
 * Buckets library manga into synthetic categories for dynamic grouping (Y3): group by source,
 * language, tag, author, status, or tracking status. Re-typed onto Mihon's immutable models from
 * the Yōkai-era `MangaLibraryDynamicGrouping`.
 *
 * Pure function: all per-manga metadata that needs a SourceManager / tracker / status lookup is
 * pre-resolved by the caller and passed in as maps keyed by manga id, so this stays unit-testable.
 *
 * Synthetic categories get **negative ids** (so they never collide with real DB categories) and
 * inherit the library-wide sort via [Category.flags] (so Mihon's `applySort` orders their items).
 * Metadata is encoded into [Category.name]; decode with [ReikaiDynamicCategory].
 *
 * BY_DEFAULT (and any non-dynamic type) returns empty; the caller routes those through Mihon's
 * own `applyGrouping`.
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
    fun build(
        library: List<LibraryManga>,
        groupType: Int,
        inheritedSort: LibrarySort,
        collapsedDynamicCategories: Set<String>,
        collapsedDynamicAtBottom: Boolean,
        unknownLabel: String,
        notTrackedLabel: String,
        ungroupedLabel: String = "",
        sourceMeta: Map<Long, Pair<String, Long>> = emptyMap(),
        trackStatuses: Map<Long, String> = emptyMap(),
        languageCodes: Map<Long, String> = emptyMap(),
        statusNames: Map<Long, String> = emptyMap(),
        languageDisplay: (langCode: String) -> String = { it },
        trackingStatusOrder: (statusName: String) -> String = { it },
    ): Map<Category, List<Long>> {
        if (library.isEmpty()) return emptyMap()

        // UNGROUPED: one flat synthetic bucket holding every manga, no per-manga metadata lookups.
        if (groupType == LibraryGroup.UNGROUPED) {
            val allIds = library.distinctBy { it.manga.id }.map { it.manga.id }
            val category = Category(id = -1L, name = ungroupedLabel, order = 0L, flags = inheritedSort.flag)
            return mapOf(category to allIds)
        }

        if (groupType !in DYNAMIC_GROUP_TYPES) return emptyMap()

        val deduplicated = library.distinctBy { it.manga.id }

        // Step 1: per-manga, the encoded bucket name(s) it belongs to. A manga can land in several
        // buckets (multiple tags / authors); distinct() guards against the same bucket twice.
        val bucketsByName = LinkedHashMap<String, MutableList<Long>>()
        for (lm in deduplicated) {
            val names = categoryNamesFor(
                lm = lm,
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
                bucketsByName.getOrPut(name) { mutableListOf() }.add(lm.manga.id)
            }
        }

        // Step 2: synthetic Category per bucket. Negative id; sort inherited via flags.
        val categories = bucketsByName.keys.mapIndexed { idx, encodedName ->
            Category(
                id = -(idx + 1).toLong(),
                name = encodedName,
                order = idx.toLong(),
                flags = inheritedSort.flag,
            )
        }

        // Step 3: order categories. BY_TRACK_STATUS gets the caller's intent order (Reading first,
        // Dropped last); everything else is alphabetical, case-insensitive, by display name.
        val sorted = categories.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { category ->
                val display = ReikaiDynamicCategory.displayName(category)
                if (groupType == LibraryGroup.BY_TRACK_STATUS) trackingStatusOrder(display) else display
            },
        )

        // Step 4: optionally push collapsed groups to the bottom.
        val finalCategories = if (collapsedDynamicAtBottom) {
            sorted.filterNot { it.name in collapsedDynamicCategories } +
                sorted.filter { it.name in collapsedDynamicCategories }
        } else {
            sorted
        }

        return finalCategories.associateWith { bucketsByName[it.name].orEmpty().toList() }
    }

    private fun categoryNamesFor(
        lm: LibraryManga,
        groupType: Int,
        unknownLabel: String,
        notTrackedLabel: String,
        sourceMeta: Map<Long, Pair<String, Long>>,
        trackStatuses: Map<Long, String>,
        languageCodes: Map<Long, String>,
        statusNames: Map<Long, String>,
        languageDisplay: (langCode: String) -> String,
    ): List<String> {
        val manga = lm.manga
        val mangaId = manga.id
        return when (groupType) {
            LibraryGroup.BY_TAG -> {
                val tags = manga.genre.orEmpty().mapNotNull { it.trim().capitalizeWords().ifBlank { null } }
                tags.ifEmpty { listOf(unknownLabel) }
            }
            LibraryGroup.BY_SOURCE -> {
                val meta = sourceMeta[mangaId] ?: return listOf("$unknownLabel${SOURCE_SPLITTER}0")
                listOf("${meta.first}$SOURCE_SPLITTER${meta.second}")
            }
            LibraryGroup.BY_LANGUAGE -> {
                val code = languageCodes[mangaId]
                if (code.isNullOrBlank()) {
                    listOf(unknownLabel)
                } else {
                    listOf("$code$LANG_SPLITTER${languageDisplay(code).ifBlank { code }}")
                }
            }
            LibraryGroup.BY_AUTHOR -> {
                val author = manga.author?.takeUnless { it.isBlank() }
                val artist = manga.artist?.takeUnless { it.isBlank() }
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
            LibraryGroup.BY_TRACK_STATUS -> listOf(trackStatuses[mangaId] ?: notTrackedLabel)
            LibraryGroup.BY_STATUS -> listOf(statusNames[mangaId] ?: unknownLabel)
            else -> emptyList()
        }
    }

    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
}
