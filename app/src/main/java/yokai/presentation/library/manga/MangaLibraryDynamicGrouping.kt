package yokai.presentation.library.manga

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.ui.library.LibraryGroup
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.lang.capitalizeWords

/**
 * Builds a `Map<Category, List<LibraryItem.Manga>>` for dynamic grouping (BY_SOURCE / BY_LANGUAGE
 * / BY_TAG / BY_AUTHOR / BY_STATUS / BY_TRACK_STATUS). Synthetic Category objects are created
 * via [Category.createCustom] so their per-category sort inherits the library-wide default
 * (mirrors legacy [eu.kanade.tachiyomi.ui.library.LibraryPresenter.getDynamicLibraryItems] at
 * `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryPresenter.kt:1128-1296`).
 *
 * Pure function: all per-manga metadata (source name/id, tracker status, language code, mapped
 * status name) is pre-resolved by the caller and passed in as maps keyed by manga id. Tests
 * mock the maps directly.
 *
 * Synthetic categories get **negative ids** (-1, -2, ...) so they don't collide with real
 * categories in any downstream Map<Category, ...> operations.
 */
object MangaLibraryDynamicGrouping {

    /**
     * @param libraryManga deduplicated favorited manga.
     * @param groupType one of [LibraryGroup.BY_TAG] / [BY_SOURCE] / [BY_LANGUAGE] / [BY_AUTHOR]
     *   / [BY_STATUS] / [BY_TRACK_STATUS]. Other values return an empty map; the caller is
     *   expected to route BY_DEFAULT through [MangaLibrarySectioner] + [MangaLibraryGrouping].
     * @param librarySortingMode the library-wide [eu.kanade.tachiyomi.ui.library.LibrarySort]
     *   mainValue. Dynamic categories inherit this for their per-category sort.
     * @param librarySortingAscending direction for the inherited sort.
     * @param collapsedDynamicCategories set of `dynamicHeaderKey()` strings the user has
     *   collapsed. Synthetic categories whose key is in this set get `isHidden = true`.
     * @param collapsedDynamicAtBottom when true, hidden categories sort to the bottom of the
     *   list regardless of alphabetical order. Mirrors `preferences.collapsedDynamicAtBottom`.
     * @param unknownLabel localized "Unknown" string for fallback buckets.
     * @param notTrackedLabel localized "Not tracked" string for [BY_TRACK_STATUS] fallback.
     * @param sourceMeta per-manga `(sourceName, sourceId)` for [BY_SOURCE]. Missing entries
     *   bucket under [unknownLabel].
     * @param trackStatuses per-manga tracker status string for [BY_TRACK_STATUS]. Missing
     *   entries bucket under [notTrackedLabel].
     * @param languageCodes per-manga language code (e.g. "en") for [BY_LANGUAGE]. Missing
     *   entries bucket under [unknownLabel].
     * @param statusNames per-manga mapped status string for [BY_STATUS]. Missing entries fall
     *   back to [unknownLabel].
     * @param languageDisplay resolves a language code to a localized display name (uses
     *   `Locale.getDisplayName`). Falls back to identity for tests.
     * @param trackingStatusOrder when [groupType] is [BY_TRACK_STATUS], orders status names by
     *   intent (Reading first, Dropped last) rather than alphabetically. Defaults to identity
     *   for non-tracking modes and for tests that don't care about ordering.
     */
    @Suppress("LongParameterList")
    fun build(
        libraryManga: List<LibraryManga>,
        groupType: Int,
        librarySortingMode: Int,
        librarySortingAscending: Boolean,
        collapsedDynamicCategories: Set<String>,
        collapsedDynamicAtBottom: Boolean,
        unknownLabel: String,
        notTrackedLabel: String,
        sourceMeta: Map<Long, Pair<String, Long>> = emptyMap(),
        trackStatuses: Map<Long, String> = emptyMap(),
        languageCodes: Map<Long, String> = emptyMap(),
        statusNames: Map<Long, String> = emptyMap(),
        languageDisplay: (langCode: String) -> String = { it },
        trackingStatusOrder: (statusName: String) -> String = { it },
    ): Map<Category, List<LibraryItem.Manga>> {
        if (libraryManga.isEmpty()) return emptyMap()
        // Only dynamic group types are handled here; the caller routes BY_DEFAULT elsewhere.
        if (groupType !in DYNAMIC_GROUP_TYPES) return emptyMap()

        // Step 1: per-manga, compute the list of category-name buckets it belongs to.
        val deduplicated = libraryManga.distinctBy { it.manga.id }
        val mangaWithNames = deduplicated.mapNotNull { lm ->
            val mangaId = lm.manga.id ?: return@mapNotNull null
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
                mangaId = mangaId,
            )
            if (names.isEmpty()) null else lm to names
        }

        // Step 2: bucket manga by category name. A manga can appear in multiple buckets
        // (BY_TAG and BY_AUTHOR with multiple comma-separated values).
        val bucketsByName = LinkedHashMap<String, MutableList<LibraryManga>>()
        for ((lm, names) in mangaWithNames) {
            for (name in names) {
                bucketsByName.getOrPut(name) { mutableListOf() }.add(lm)
            }
        }

        // Step 3: build synthetic Category objects. Each gets a negative id so it can't collide
        // with real (DB) categories.
        val categories = bucketsByName.keys.mapIndexed { idx, encodedName ->
            Category.createCustom(encodedName, librarySortingMode, librarySortingAscending).apply {
                this.id = -(idx + 1)
                // Decode source / language pseudo-keys back into the canonical `name` + sourceId
                // / langId fields so `dynamicHeaderKey()` can round-trip them.
                if (encodedName.contains(Category.sourceSplitter)) {
                    val split = encodedName.split(Category.sourceSplitter)
                    this.name = split.first()
                    sourceId = split.last().toLongOrNull()
                } else if (encodedName.contains(Category.langSplitter)) {
                    val split = encodedName.split(Category.langSplitter)
                    this.name = split.last()
                    langId = split.first()
                }
                isHidden = dynamicHeaderKey() in collapsedDynamicCategories
            }
        }

        // Step 4: sort categories. BY_TRACK_STATUS gets a custom order via the caller's
        // trackingStatusOrder map; everything else is alphabetical, case-insensitive.
        val sortedCategories = categories.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { cat ->
                if (groupType == LibraryGroup.BY_TRACK_STATUS) trackingStatusOrder(cat.name) else cat.name
            },
        )

        // Step 5: optionally push collapsed-dynamic categories to the bottom.
        val finalCategories = if (collapsedDynamicAtBottom) {
            sortedCategories.filterNot { it.isHidden } + sortedCategories.filter { it.isHidden }
        } else {
            sortedCategories
        }

        // Step 6: associate each category with its bucket (via dynamicHeaderKey which round-trips
        // back to the encoded bucket name) and wrap items as LibraryItem.Manga.
        return finalCategories.associateWith { category ->
            val bucketKey = category.dynamicHeaderKey()
            bucketsByName[bucketKey].orEmpty().map { lm ->
                LibraryItem.Manga(libraryManga = lm)
            }
        }
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
        mangaId: Long,
    ): List<String> {
        val manga = lm.manga
        return when (groupType) {
            LibraryGroup.BY_TAG -> {
                val genre = manga.genre
                if (genre.isNullOrBlank()) {
                    listOf(unknownLabel)
                } else {
                    val parsed = genre.split(",").mapNotNull {
                        val tag = it.trim().capitalizeWords()
                        tag.ifBlank { null }
                    }
                    parsed.ifEmpty { listOf(unknownLabel) }
                }
            }
            LibraryGroup.BY_SOURCE -> {
                val (name, sourceId) = sourceMeta[mangaId]
                    ?: return listOf("$unknownLabel${Category.sourceSplitter}0")
                listOf("$name${Category.sourceSplitter}$sourceId")
            }
            LibraryGroup.BY_LANGUAGE -> {
                val langCode = languageCodes[mangaId]
                if (langCode.isNullOrBlank()) {
                    listOf(unknownLabel)
                } else {
                    val display = languageDisplay(langCode).ifBlank { langCode }
                    listOf("$langCode${Category.langSplitter}$display")
                }
            }
            LibraryGroup.BY_AUTHOR -> {
                val author = manga.author?.takeUnless { it.isNullOrBlank() }
                val artist = manga.artist?.takeUnless { it.isNullOrBlank() }
                if (author == null && artist == null) {
                    listOf(unknownLabel)
                } else {
                    listOfNotNull(author, artist).flatMap { combined ->
                        combined.split(",", "/", " x ", " - ", ignoreCase = true)
                            .mapNotNull { name ->
                                val candidate = name.trim()
                                candidate.ifBlank { null }
                            }
                    }.distinct().ifEmpty { listOf(unknownLabel) }
                }
            }
            LibraryGroup.BY_TRACK_STATUS -> {
                listOf(trackStatuses[mangaId] ?: notTrackedLabel)
            }
            LibraryGroup.BY_STATUS -> {
                listOf(statusNames[mangaId] ?: unknownLabel)
            }
            else -> emptyList()
        }
    }

    private val DYNAMIC_GROUP_TYPES = setOf(
        LibraryGroup.BY_TAG,
        LibraryGroup.BY_SOURCE,
        LibraryGroup.BY_LANGUAGE,
        LibraryGroup.BY_AUTHOR,
        LibraryGroup.BY_STATUS,
        LibraryGroup.BY_TRACK_STATUS,
    )
}
