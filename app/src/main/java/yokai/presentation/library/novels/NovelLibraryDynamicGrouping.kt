package yokai.presentation.library.novels

import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.ui.library.LibraryGroup
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.lang.capitalizeWords

/**
 * Novel-side parallel of [yokai.presentation.library.manga.MangaLibraryDynamicGrouping].
 * Builds a `Map<NovelCategory, List<LibraryItem.Novel>>` for dynamic grouping (BY_SOURCE /
 * BY_TAG / BY_AUTHOR / BY_STATUS / BY_TRACK_STATUS).
 *
 * Diverges from the manga helper in two places:
 *
 * - **No BY_LANGUAGE mode.** [yokai.domain.novel.models.Novel] has no language field
 *   (lnreader plugins don't report a per-novel language code), so the synthetic category
 *   for that group type would always bucket everything under "Unknown". Passing
 *   `LibraryGroup.BY_LANGUAGE` returns an empty map. If novels later grow a language
 *   field, restore the case from the manga side verbatim.
 * - **Source ids are `String`** (lnreader plugin ids), so [sourceMeta] is keyed by novelId
 *   to a `(displayName, sourceId)` pair both of String type. [NovelCategory.sourceId] is
 *   also String-typed.
 *
 * Pure function: all per-novel metadata (source info, tracker status, mapped status name)
 * is pre-resolved by the caller and passed in as maps keyed by novelId.
 */
object NovelLibraryDynamicGrouping {

    @Suppress("LongParameterList")
    fun build(
        libraryNovel: List<LibraryNovel>,
        groupType: Int,
        librarySortingMode: Int,
        librarySortingAscending: Boolean,
        collapsedDynamicCategories: Set<String>,
        collapsedDynamicAtBottom: Boolean,
        unknownLabel: String,
        notTrackedLabel: String,
        sourceMeta: Map<Long, Pair<String, String>> = emptyMap(),
        trackStatuses: Map<Long, String> = emptyMap(),
        statusNames: Map<Long, String> = emptyMap(),
        trackingStatusOrder: (statusName: String) -> String = { it },
    ): Map<NovelCategory, List<LibraryItem.Novel>> {
        if (libraryNovel.isEmpty()) return emptyMap()
        if (groupType !in DYNAMIC_GROUP_TYPES) return emptyMap()

        val deduplicated = libraryNovel.distinctBy { it.novel.id }
        val novelWithNames = deduplicated.mapNotNull { ln ->
            val novelId = ln.novel.id ?: return@mapNotNull null
            val names = categoryNamesFor(
                ln = ln,
                groupType = groupType,
                unknownLabel = unknownLabel,
                notTrackedLabel = notTrackedLabel,
                sourceMeta = sourceMeta,
                trackStatuses = trackStatuses,
                statusNames = statusNames,
                novelId = novelId,
            )
            if (names.isEmpty()) null else ln to names
        }

        val bucketsByName = LinkedHashMap<String, MutableList<LibraryNovel>>()
        for ((ln, names) in novelWithNames) {
            for (name in names.distinct()) {
                bucketsByName.getOrPut(name) { mutableListOf() }.add(ln)
            }
        }

        val categories = bucketsByName.keys.mapIndexed { idx, encodedName ->
            NovelCategory.createCustom(encodedName, librarySortingMode, librarySortingAscending).apply {
                this.id = -(idx + 1)
                if (encodedName.contains(NovelCategory.sourceSplitter)) {
                    val split = encodedName.split(NovelCategory.sourceSplitter)
                    this.name = split.first()
                    sourceId = split.last()
                }
                isHidden = dynamicHeaderKey() in collapsedDynamicCategories
            }
        }

        val sortedCategories = categories.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { cat ->
                if (groupType == LibraryGroup.BY_TRACK_STATUS) trackingStatusOrder(cat.name) else cat.name
            },
        )

        val finalCategories = if (collapsedDynamicAtBottom) {
            sortedCategories.filterNot { it.isHidden } + sortedCategories.filter { it.isHidden }
        } else {
            sortedCategories
        }

        return finalCategories.associateWith { category ->
            val bucketKey = category.dynamicHeaderKey()
            bucketsByName[bucketKey].orEmpty().map { ln ->
                LibraryItem.Novel(libraryNovel = ln)
            }
        }
    }

    private fun categoryNamesFor(
        ln: LibraryNovel,
        groupType: Int,
        unknownLabel: String,
        notTrackedLabel: String,
        sourceMeta: Map<Long, Pair<String, String>>,
        trackStatuses: Map<Long, String>,
        statusNames: Map<Long, String>,
        novelId: Long,
    ): List<String> {
        val novel = ln.novel
        return when (groupType) {
            LibraryGroup.BY_TAG -> {
                val genres = novel.genres
                if (genres.isNullOrEmpty()) {
                    listOf(unknownLabel)
                } else {
                    val parsed = genres.mapNotNull {
                        val tag = it.trim().capitalizeWords()
                        tag.ifBlank { null }
                    }
                    parsed.ifEmpty { listOf(unknownLabel) }
                }
            }
            LibraryGroup.BY_SOURCE -> {
                val (name, sourceId) = sourceMeta[novelId]
                    ?: return listOf("$unknownLabel${NovelCategory.sourceSplitter}")
                listOf("$name${NovelCategory.sourceSplitter}$sourceId")
            }
            LibraryGroup.BY_AUTHOR -> {
                val author = novel.author?.takeUnless { it.isNullOrBlank() }
                val artist = novel.artist?.takeUnless { it.isNullOrBlank() }
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
                listOf(trackStatuses[novelId] ?: notTrackedLabel)
            }
            LibraryGroup.BY_STATUS -> {
                listOf(statusNames[novelId] ?: unknownLabel)
            }
            else -> emptyList()
        }
    }

    private val DYNAMIC_GROUP_TYPES = setOf(
        LibraryGroup.BY_TAG,
        LibraryGroup.BY_SOURCE,
        LibraryGroup.BY_AUTHOR,
        LibraryGroup.BY_STATUS,
        LibraryGroup.BY_TRACK_STATUS,
    )
}
