package reikai.presentation.library.novels

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelCategories
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.NovelCategory
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy

/**
 * Drives the novel half of the Library tab (P5 S6). Lean by design: it reads the favorited novels +
 * novel categories reactively, disguises each novel as the library's manga-shaped [LibraryItem]
 * (negative id), groups them under their novel categories, and exposes the same accessor surface
 * [eu.kanade.tachiyomi.ui.library.LibraryScreenModel.State] does, so `LibraryTab` can feed the
 * existing views from either model based on the content-type chip. Mihon's library core is untouched.
 *
 * Dynamic grouping, merge, trackers, and novel-specific filters/sorts beyond the basics are deferred
 * (see the S6 plan); display settings (view mode, columns, hopper, badges) stay shared with manga.
 */
class NovelLibraryScreenModel :
    StateScreenModel<NovelLibraryScreenModel.State>(State()) {

    private val context: Application by injectLazy()
    private val novelRepository: NovelRepository by injectLazy()
    private val getNovelCategories: GetNovelCategories by injectLazy()
    private val novelPreferences: NovelPreferences by injectLazy()
    private val libraryPreferences: LibraryPreferences by injectLazy()
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences by injectLazy()
    private val sourceManager: NovelSourceManager by injectLazy()

    /** Sticky Manga/Novels chip for the Library tab (owned here so it's read outside a Composable). */
    val contentType: StateFlow<ContentType> = reikaiLibraryPreferences.libraryContentType.changes()
        .stateIn(screenModelScope, SharingStarted.Eagerly, reikaiLibraryPreferences.libraryContentType.get())

    fun setContentType(type: ContentType) = reikaiLibraryPreferences.libraryContentType.set(type)

    private val searchQuery = MutableStateFlow<String?>(null)
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    // Keyed by category name (header key), matching the manga collapse convention. Session-scoped.
    private val collapsedCategories = MutableStateFlow<Set<String>>(emptySet())

    init {
        screenModelScope.launchIO {
            combine(
                getNovelCategories.subscribe(),
                novelRepository.getLibraryNovelAsFlow(),
                searchQuery,
                selection,
                badgePrefsFlow(),
            ) { categories, library, query, sel, badges ->
                buildState(categories, library, query, sel, badges)
            }.collectLatest { built ->
                mutableState.update { current ->
                    built.copy(collapsedCategories = collapsedCategories.value, activeCategoryIndex = current.activeCategoryIndex)
                }
            }
        }
        screenModelScope.launchIO {
            collapsedCategories.collectLatest { collapsed ->
                mutableState.update { it.copy(collapsedCategories = collapsed) }
            }
        }
    }

    private fun badgePrefsFlow(): Flow<BadgePrefs> = combine(
        libraryPreferences.downloadBadge.changes(),
        libraryPreferences.unreadBadge.changes(),
        libraryPreferences.languageBadge.changes(),
    ) { download, unread, language -> BadgePrefs(download, unread, language) }

    private fun buildState(
        categories: List<NovelCategory>,
        library: List<LibraryNovel>,
        query: String?,
        sel: Set<Long>,
        badges: BadgePrefs,
    ): State {
        val items = library
            .filter { query.isNullOrBlank() || it.matchesQuery(query) }
            .map { novel ->
                val lang = sourceManager.get(novel.novel.source)?.lang.orEmpty()
                novel.toLibraryItem(badges.download, badges.unread, badges.language, lang)
            }
        val byId = items.associateBy { it.id }

        // Bucket item ids by category id; uncategorized (no real category) goes to Default (id 0).
        val byCategory = LinkedHashMap<Long, MutableList<Long>>()
        items.forEach { item ->
            val cats = item.libraryManga.categories.filter { it != NovelCategory.UNCATEGORIZED_ID }
            if (cats.isEmpty()) {
                byCategory.getOrPut(NovelCategory.UNCATEGORIZED_ID) { mutableListOf() }.add(item.id)
            } else {
                cats.forEach { c -> byCategory.getOrPut(c) { mutableListOf() }.add(item.id) }
            }
        }

        val defaultCategory = Category(NovelCategory.UNCATEGORIZED_ID, context.stringResource(MR.strings.label_default), 0L, 0L)
        val allCategories = (listOf(defaultCategory) + categories.map { it.toCategory() }).sortedBy { it.order }
        val grouped = allCategories.mapNotNull { category ->
            val ids = byCategory[category.id] ?: return@mapNotNull null
            // Alphabetical by title for now (richer sort lands in S6 slice 4).
            category to ids.sortedBy { id -> byId[id]?.libraryManga?.manga?.title?.lowercase().orEmpty() }
        }

        // Item id (negative) -> (source, url) so LibraryTab can open the novel (the synthetic Manga
        // drops the String source, so it can't be recovered from the item itself).
        val routes = library.associate { -it.novel.id to NovelRoute(it.novel.source, it.novel.url) }

        return State(
            isLoading = false,
            searchQuery = query,
            selection = sel,
            groupedFavorites = grouped,
            favoritesById = byId,
            novelRoutes = routes,
        )
    }

    // --- search / selection / collapse mutators (read by LibraryTab) ---

    fun search(query: String?) { searchQuery.value = query }

    fun clearSelection() { selection.value = emptySet() }

    fun toggleSelection(novelItemId: Long) {
        selection.update { if (novelItemId in it) it - novelItemId else it + novelItemId }
    }

    fun toggleCategoryCollapse(headerKey: String) {
        collapsedCategories.update { if (headerKey in it) it - headerKey else it + headerKey }
    }

    fun updateActiveCategoryIndex(index: Int) {
        mutableState.update { it.copy(activeCategoryIndex = index) }
    }

    private data class BadgePrefs(val download: Boolean, val unread: Boolean, val language: Boolean)

    data class State(
        val isLoading: Boolean = true,
        val searchQuery: String? = null,
        val selection: Set<Long> = emptySet(),
        val collapsedCategories: Set<String> = emptySet(),
        val activeCategoryIndex: Int = 0,
        private val groupedFavorites: List<Pair<Category, List<Long>>> = emptyList(),
        private val favoritesById: Map<Long, LibraryItem> = emptyMap(),
        private val novelRoutes: Map<Long, NovelRoute> = emptyMap(),
    ) {
        val displayedCategories: List<Category> = groupedFavorites.map { it.first }

        private val groupedById: Map<Long, List<Long>> by lazy {
            groupedFavorites.associate { it.first.id to it.second }
        }

        val coercedActiveCategoryIndex = activeCategoryIndex.coerceIn(
            0,
            displayedCategories.lastIndex.coerceAtLeast(0),
        )

        val activeCategory: Category? = displayedCategories.getOrNull(coercedActiveCategoryIndex)

        val isLibraryEmpty = favoritesById.isEmpty()

        val selectionMode = selection.isNotEmpty()

        val selectedNovelIds: List<Long> by lazy { selection.map { -it } }

        fun getItemsForCategory(category: Category): List<LibraryItem> =
            groupedById[category.id].orEmpty().mapNotNull { favoritesById[it] }

        fun getItemCountForCategory(category: Category): Int? = groupedById[category.id]?.size

        /** (source, url) for the disguised item id (negative), to open the novel details screen. */
        fun routeFor(itemId: Long): NovelRoute? = novelRoutes[itemId]
    }

    data class NovelRoute(val source: String, val url: String)
}

private fun NovelCategory.toCategory(): Category = Category(id = id, name = name, order = order, flags = flags)

private fun LibraryNovel.matchesQuery(query: String): Boolean {
    val n = novel
    return n.title.contains(query, true) ||
        (n.author?.contains(query, true) ?: false) ||
        (n.artist?.contains(query, true) ?: false) ||
        (n.genre?.any { it.contains(query, true) } ?: false)
}
