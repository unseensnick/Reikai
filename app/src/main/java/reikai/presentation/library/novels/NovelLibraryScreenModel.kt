package reikai.presentation.library.novels

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import reikai.data.novel.NovelStatusCode
import reikai.domain.category.CATEGORY_HIDDEN_MASK
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelCategoryRepository
import reikai.domain.novel.NovelChapterAggregation
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelCategories
import reikai.domain.novel.interactor.SetNovelCategories
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.NovelCategory
import reikai.domain.novel.model.NovelCategoryUpdate
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelLibrarySort
import reikai.domain.novel.model.comparator
import reikai.domain.novel.model.toCategory
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSourceManager
import reikai.presentation.library.reikaiSortCategories
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.util.Locale
import kotlin.random.Random

/**
 * Drives the novel half of the Library tab (P5 S6). It reads the favorited novels + novel categories
 * reactively, disguises each novel as the library's manga-shaped [LibraryItem] (negative id), filters
 * and per-category-sorts them, and exposes the same accessor surface
 * [eu.kanade.tachiyomi.ui.library.LibraryScreenModel.State] does so `LibraryTab` can feed the existing
 * views from either model based on the content-type chip. Mihon's library core is untouched.
 *
 * Selection is keyed by the negative synthetic id; [State.selectedNovelIds] maps back to real novel ids
 * for the multi-select actions (download / delete / change-category / mark-read). Dynamic grouping,
 * merge, and trackers stay deferred; display settings stay shared with manga.
 */
class NovelLibraryScreenModel :
    StateScreenModel<NovelLibraryScreenModel.State>(State()) {

    private val context: Application by injectLazy()
    private val novelRepository: NovelRepository by injectLazy()
    private val novelChapterRepository: NovelChapterRepository by injectLazy()
    private val novelCategoryRepository: NovelCategoryRepository by injectLazy()
    private val novelDownloadManager: NovelDownloadManager by injectLazy()
    private val getNovelCategories: GetNovelCategories by injectLazy()
    private val setNovelCategories: SetNovelCategories by injectLazy()
    private val libraryPreferences: LibraryPreferences by injectLazy()
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences by injectLazy()
    private val sourceManager: NovelSourceManager by injectLazy()
    private val mergeManager: NovelMergeManager by injectLazy()
    private val installer: LnPluginInstaller by injectLazy()

    /** Sticky Manga/Novels chip for the Library tab (owned here so it's read outside a Composable). */
    val contentType: StateFlow<ContentType> = reikaiLibraryPreferences.libraryContentType.changes()
        .stateIn(screenModelScope, SharingStarted.Eagerly, reikaiLibraryPreferences.libraryContentType.get())

    fun setContentType(type: ContentType) = reikaiLibraryPreferences.libraryContentType.set(type)

    private val searchQuery = MutableStateFlow<String?>(null)
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    // Keyed by category name (header key), matching the manga collapse convention. Session-scoped.
    private val collapsedCategories = MutableStateFlow<Set<String>>(emptySet())

    // Anchor for range-select; not reactive (mirrors LibraryScreenModel.lastSelectionCategory).
    private var lastSelectionCategory: Long? = null

    private val mutableDialog = MutableStateFlow<Dialog?>(null)
    val dialog: StateFlow<Dialog?> = mutableDialog.asStateFlow()

    init {
        // Load the plugin host so the library can resolve each novel's source (lang + source-icon
        // badges); the source flow below re-emits buildState once the sources register.
        screenModelScope.launchIO { runCatching { installer.ensureLoaded() } }
        screenModelScope.launchIO {
            combine(
                getNovelCategories.subscribe(),
                // Re-emit when sources (un)register so `sourceManager.get(...)` resolves once loaded.
                novelRepository.getLibraryNovelAsFlow().combine(sourceManager.sources) { library, _ -> library },
                searchQuery,
                selection,
                settingsFlow(),
            ) { categories, library, query, sel, settings ->
                buildState(categories, library, query, sel, settings)
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
        reikaiLibraryPreferences.sourceBadge.changes(),
    ) { download, unread, language, source -> BadgePrefs(download, unread, language, source) }

    /** Folds the badge, sort, and filter prefs into one flow so the main combine stays at its 5-arg max. */
    private fun settingsFlow(): Flow<LibrarySettings> {
        val miscFlow = combine(
            reikaiLibraryPreferences.novelLibraryDefaultSort.changes(),
            reikaiLibraryPreferences.novelLibraryRandomSeed.changes(),
            libraryPreferences.showContinueReadingButton.changes(),
            reikaiLibraryPreferences.showHiddenCategories.changes(),
            reikaiLibraryPreferences.categorySortOrder.changes(),
        ) { sort, seed, cont, showHidden, catSort -> Misc(sort, seed, cont, showHidden, catSort) }
        val filterFlow = combine(
            reikaiLibraryPreferences.novelLibraryFilterDownloaded.changes(),
            reikaiLibraryPreferences.novelLibraryFilterUnread.changes(),
            reikaiLibraryPreferences.novelLibraryFilterStarted.changes(),
            reikaiLibraryPreferences.novelLibraryFilterCompleted.changes(),
            reikaiLibraryPreferences.novelLibraryFilterBookmarked.changes(),
        ) { d, u, s, c, b -> NovelFilters(d, u, s, c, b) }
        val mergeFlow = combine(
            reikaiLibraryPreferences.novelManualMerges.changes(),
            reikaiLibraryPreferences.novelManualUnmerges.changes(),
            reikaiLibraryPreferences.novelAutoMergeSameTitle.changes(),
            reikaiLibraryPreferences.novelAutoMergeRequireAuthor.changes(),
            reikaiLibraryPreferences.showNovelMergeSourceIcons.changes(),
        ) { merges, unmerges, auto, requireAuthor, showIcons ->
            MergeSettings(merges, unmerges, auto, requireAuthor, showIcons)
        }
        return combine(badgePrefsFlow(), miscFlow, filterFlow, mergeFlow) { badges, misc, filters, merge ->
            LibrarySettings(
                badges, misc.defaultSort, misc.randomSeed, misc.showContinue, misc.showHidden,
                filters, merge, misc.categorySortOrder,
            )
        }
    }

    private fun buildState(
        categories: List<NovelCategory>,
        library: List<LibraryNovel>,
        query: String?,
        sel: Set<Long>,
        settings: LibrarySettings,
    ): State {
        val filtered = library.filter { novel ->
            (query.isNullOrBlank() || novel.matchesQuery(query)) && settings.filters.matches(novel)
        }
        // Collapse merged groups into one representative entry (the most-chapters novel).
        val collapsed = NovelMergeCollapse.collapse(
            filtered,
            settings.merge.manualMerges,
            settings.merge.manualUnmerges,
            settings.merge.autoMergeSameTitle,
            settings.merge.requireAuthor,
        )
        // Keyed by the representative's negative synthetic id (== the LibraryItem id), for the comparator.
        val novelById = collapsed.associate { -it.representative.novel.id to it.representative }
        // novelId -> source id, to resolve each grouped source's icon for the merge badge.
        val sourceByNovelId = library.associate { it.novel.id to it.novel.source }
        val items = collapsed.map { group ->
            val rep = group.representative
            // lnreader plugins mostly declare lang as a full English name ("English"); the badge wants a
            // 2-char code like the manga side, so reduce it (codes pass through unchanged).
            val source = sourceManager.get(rep.novel.source)
            val lang = languageCodeOf(source?.lang.orEmpty())
            val item = rep.toLibraryItem(
                settings.badges.download,
                settings.badges.unread,
                settings.badges.language,
                lang,
                sourceBadge = settings.badges.source,
                sourceSite = source?.site,
                sourceIconUrl = source?.iconUrl,
            )
            if (group.memberIds.size > 1) {
                // Stamp the merge badge (group member ids, negative) + summed downloads onto the rep.
                // When the merge-icon setting is on, also resolve each grouped source's icon URL.
                val iconUrls = if (settings.merge.showSourceIcons) {
                    group.memberIds
                        .mapNotNull { id -> sourceByNovelId[id]?.let { sourceManager.get(it)?.iconUrl } }
                        .distinct()
                } else {
                    emptyList()
                }
                item.copy(
                    downloadCount = group.totalDownloadCount.toInt(),
                    relatedMangaIds = group.memberIds.map { -it },
                    badges = item.badges.copy(
                        downloadCount = if (settings.badges.download) group.totalDownloadCount.toInt() else 0,
                        mergedSourceIconUrls = iconUrls,
                    ),
                )
            } else {
                item
            }
        }
        val byId = items.associateBy { it.id }
        val flagsByCat = categories.associate { it.id to it.flags }

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

        // Encode the resolved default sort into the synthesized Default category's flags so its header
        // label reflects the actual sort (it's stored in a global pref, not a DB row). NovelLibrarySort
        // mirrors LibrarySort's bit layout, so the shared header's `category.sort` decodes it correctly.
        val defaultCategory =
            Category(NovelCategory.UNCATEGORIZED_ID, context.stringResource(MR.strings.label_default), 0L, settings.defaultSort)
        val visibleCategories = if (settings.showHidden) {
            categories
        } else {
            categories.filterNot { (it.flags and CATEGORY_HIDDEN_MASK) == CATEGORY_HIDDEN_MASK }
        }
        // Manual DB order first, then the Reikai category-sort-order pref (Off/A->Z/Z->A), matching the
        // manga library so the shared Display setting reorders novel categories too (system pinned top).
        val allCategories = reikaiSortCategories(
            (listOf(defaultCategory) + visibleCategories.map { it.toCategory() }).sortedBy { it.order },
            settings.categorySortOrder,
        )
        val defaultSort = NovelLibrarySort.fromFlag(settings.defaultSort)
        val grouped = allCategories.mapNotNull { category ->
            val ids = byCategory[category.id] ?: return@mapNotNull null
            val sort = sortFor(category.id, flagsByCat[category.id] ?: 0L, defaultSort)
            val comparator = sort.comparator(settings.randomSeed)
            val sorted = ids.sortedWith { a, b -> comparator.compare(novelById.getValue(a), novelById.getValue(b)) }
            category to sorted
        }

        // Item id (negative) -> (source, url) so LibraryTab can open the (representative) novel.
        val routes = collapsed.associate {
            -it.representative.novel.id to NovelRoute(it.representative.novel.source, it.representative.novel.url)
        }

        return State(
            isLoading = false,
            searchQuery = query,
            selection = sel,
            groupedFavorites = grouped,
            favoritesById = byId,
            novelRoutes = routes,
            categorySortFlags = flagsByCat,
            defaultSortFlag = settings.defaultSort,
            hasActiveFilters = settings.filters.hasActive,
            showContinueButton = settings.showContinue,
        )
    }

    private fun sortFor(categoryId: Long, flags: Long, default: NovelLibrarySort): NovelLibrarySort =
        if (categoryId == NovelCategory.UNCATEGORIZED_ID) default else NovelLibrarySort.forCategory(flags, default)

    // --- search / selection / collapse mutators (read by LibraryTab) ---

    fun search(query: String?) { searchQuery.value = query }

    fun clearSelection() {
        lastSelectionCategory = null
        selection.value = emptySet()
    }

    fun toggleSelection(categoryId: Long, itemId: Long) {
        selection.update { if (itemId in it) it - itemId else it + itemId }
        lastSelectionCategory = categoryId.takeIf { selection.value.isNotEmpty() }
    }

    /** Selects every item between the last-selected and [itemId] within the same category. */
    fun toggleRangeSelection(categoryId: Long, itemId: Long) {
        val items = state.value.itemIdsForCategory(categoryId)
        val last = selection.value.lastOrNull()
        if (lastSelectionCategory != categoryId || last == null || last !in items || itemId !in items) {
            selection.update { it + itemId }
        } else {
            val from = items.indexOf(last)
            val to = items.indexOf(itemId)
            val range = items.subList(minOf(from, to), maxOf(from, to) + 1)
            selection.update { it + range }
        }
        lastSelectionCategory = categoryId
    }

    fun selectAll() {
        lastSelectionCategory = null
        val ids = state.value.itemIdsForCategory(state.value.activeCategory?.id)
        selection.update { it + ids }
    }

    fun selectAllInCategory(categoryId: Long) {
        lastSelectionCategory = null
        val ids = state.value.itemIdsForCategory(categoryId)
        selection.update { sel -> if (ids.isNotEmpty() && ids.all { it in sel }) sel - ids.toSet() else sel + ids }
    }

    fun invertSelection() {
        lastSelectionCategory = null
        val ids = state.value.itemIdsForCategory(state.value.activeCategory?.id)
        selection.update { sel -> sel + ids.filterNot { it in sel } - ids.filter { it in sel }.toSet() }
    }

    fun toggleCategoryCollapse(headerKey: String) {
        collapsedCategories.update { if (headerKey in it) it - headerKey else it + headerKey }
    }

    /** Collapse all categories if any is expanded, else expand all (the hopper "toggle collapse"). */
    fun toggleAllCategoriesCollapsed(categories: List<Category>) {
        val keys = categories.map { it.id.toString() }.toSet()
        collapsedCategories.update { current -> if (current.containsAll(keys)) current - keys else current + keys }
    }

    fun updateActiveCategoryIndex(index: Int) {
        mutableState.update { it.copy(activeCategoryIndex = index) }
    }

    // --- multi-select actions ---

    /** Manually merge the selected novels into one group (covers both library views). */
    fun mergeSelection() {
        val ids = state.value.selectedNovelIds
        if (ids.size < 2) return
        screenModelScope.launchIO {
            mergeManager.mergeNovels(ids)
            clearSelection()
        }
    }

    /** Split the selected novels out of their merge groups (no-op for non-merged selections). */
    fun unmergeSelection() {
        val ids = state.value.selectedNovelIds
        if (ids.isEmpty()) return
        screenModelScope.launchIO {
            mergeManager.unmergeNovels(ids)
            clearSelection()
        }
    }

    fun markReadSelection(read: Boolean) {
        val novelIds = state.value.selectedNovelIds
        screenModelScope.launchIO {
            val chapterIds = novelIds.flatMap { id -> novelChapterRepository.getByNovelId(id).map { it.id } }
            if (chapterIds.isNotEmpty()) novelChapterRepository.setReadBulk(chapterIds, read)
            clearSelection()
        }
    }

    fun performDownloadAction(action: DownloadAction) {
        val novelIds = state.value.selectedNovelIds
        screenModelScope.launchIO {
            novelIds.forEach { id ->
                val chapters = novelChapterRepository.getByNovelId(id).sortedBy { it.sourceOrder }
                val unread = chapters.filterNot { it.read }
                val targets = when (action) {
                    DownloadAction.NEXT_1_CHAPTER -> unread.take(1)
                    DownloadAction.NEXT_5_CHAPTERS -> unread.take(5)
                    DownloadAction.NEXT_10_CHAPTERS -> unread.take(10)
                    DownloadAction.NEXT_25_CHAPTERS -> unread.take(25)
                    DownloadAction.UNREAD_CHAPTERS -> unread
                    DownloadAction.BOOKMARKED_CHAPTERS -> chapters.filter { it.bookmark }
                }
                if (targets.isNotEmpty()) novelDownloadManager.downloadChapters(targets)
            }
            clearSelection()
        }
    }

    fun openChangeCategoryDialog() {
        screenModelScope.launchIO {
            val novelIds = state.value.selectedNovelIds
            // All non-default categories, not just the ones currently shown (empty categories are
            // hidden from the library grid but must still be assignable here).
            val categories = getNovelCategories.await().filterNot { it.isSystemCategory }.map { it.toCategory() }
            val perNovel = novelIds.map { getNovelCategories.awaitByNovelId(it).map { c -> c.id }.toSet() }
            val common = perNovel.reduceOrNull { a, b -> a intersect b } ?: emptySet()
            val mix = perNovel.flatten().toSet() - common
            val preselected: List<CheckboxState<Category>> = categories.map { cat ->
                when (cat.id) {
                    in common -> CheckboxState.State.Checked(cat)
                    in mix -> CheckboxState.TriState.Exclude(cat)
                    else -> CheckboxState.State.None(cat)
                }
            }
            mutableDialog.value = Dialog.ChangeCategory(novelIds, preselected)
        }
    }

    fun setNovelCategories(novelIds: List<Long>, addCategories: List<Long>, removeCategories: List<Long>) {
        screenModelScope.launchIO {
            novelIds.forEach { novelId ->
                val current = getNovelCategories.awaitByNovelId(novelId).map { it.id }
                val new = (current - removeCategories.toSet() + addCategories).distinct()
                setNovelCategories.await(novelId, new)
            }
            clearSelection()
            dismissDialog()
        }
    }

    fun openDeleteDialog() {
        mutableDialog.value = Dialog.Delete(state.value.selectedNovelIds)
    }

    fun removeNovels(novelIds: List<Long>, deleteFromLibrary: Boolean, deleteDownloads: Boolean) {
        screenModelScope.launchIO {
            novelIds.forEach { novelId ->
                if (deleteFromLibrary) {
                    novelRepository.getById(novelId)?.let { novelRepository.update(it.copy(favorite = false)) }
                }
                if (deleteDownloads) {
                    val downloaded = novelChapterRepository.getByNovelId(novelId).filter { it.isDownloaded }
                    if (downloaded.isNotEmpty()) novelDownloadManager.deleteChapters(downloaded)
                }
            }
            clearSelection()
            dismissDialog()
        }
    }

    /** The chapter to resume + the reading order the reader should walk. For a merged novel this pools
     *  the whole group (the unified cross-source list the details "All" view shows), so the resume +
     *  the reader's prev/next span every grouped source, not just the representative one. */
    suspend fun getResume(repNovelId: Long): NovelResume? {
        val rep = novelRepository.getById(repNovelId) ?: return null
        val memberIds = mergeManager.computeRelatedNovelIds(rep.id, rep.title, rep.author).toList()
        val ordered = if (memberIds.size <= 1) {
            novelChapterRepository.getByNovelId(repNovelId).sortedBy { it.sourceOrder }
        } else {
            val byNovel = memberIds.associateWith { novelChapterRepository.getByNovelId(it) }
            val sourceIdByNovel = memberIds.associateWith { id -> novelRepository.getById(id)?.source.orEmpty() }
            NovelChapterAggregation.aggregate(byNovel, sourceIdByNovel, reikaiLibraryPreferences.preferredNovelSources.get())
                // chapterNumber is the cross-source reading order (sourceOrder isn't comparable across sources).
                .sortedBy { it.chapterNumber }
        }
        val first = ordered.firstOrNull { !it.read } ?: return null
        return NovelResume(first, ordered.map { it.id })
    }

    /** The next-unread chapter to open + the reading-order chapter ids the reader should navigate. */
    data class NovelResume(val chapter: NovelChapter, val chapterIds: List<Long>)

    // --- settings dialog (sort / filter) ---

    fun openSettingsDialog(categoryId: Long, initialTab: Int = 0) {
        mutableDialog.value = Dialog.Settings(categoryId, initialTab)
    }

    fun dismissDialog() { mutableDialog.value = null }

    /** Sets the sort for a category (or the library default for the synthesized Default category). */
    fun setSort(categoryId: Long, type: NovelLibrarySort.Type, isAscending: Boolean) {
        if (type == NovelLibrarySort.Type.Random) {
            reikaiLibraryPreferences.novelLibraryRandomSeed.set(Random.nextLong())
        }
        val flag = NovelLibrarySort(type, isAscending).toFlag()
        if (categoryId == NovelCategory.UNCATEGORIZED_ID) {
            reikaiLibraryPreferences.novelLibraryDefaultSort.set(flag)
        } else {
            screenModelScope.launchIO {
                // Preserve the category's other flag bits (e.g. hidden); only rewrite the sort bits.
                val current = state.value.flagsForCategory(categoryId)
                val newFlags = (current and NovelLibrarySort.FLAGS_MASK.inv()) or flag
                novelCategoryRepository.update(NovelCategoryUpdate(id = categoryId, flags = newFlags))
            }
        }
    }

    // Filter prefs exposed for the settings dialog (read via collectAsState, cycled via toggleFilter).
    val filterDownloaded: Preference<TriState> get() = reikaiLibraryPreferences.novelLibraryFilterDownloaded
    val filterUnread: Preference<TriState> get() = reikaiLibraryPreferences.novelLibraryFilterUnread
    val filterStarted: Preference<TriState> get() = reikaiLibraryPreferences.novelLibraryFilterStarted
    val filterCompleted: Preference<TriState> get() = reikaiLibraryPreferences.novelLibraryFilterCompleted
    val filterBookmarked: Preference<TriState> get() = reikaiLibraryPreferences.novelLibraryFilterBookmarked

    fun toggleFilter(pref: Preference<TriState>) {
        pref.set(
            when (pref.get()) {
                TriState.DISABLED -> TriState.ENABLED_IS
                TriState.ENABLED_IS -> TriState.ENABLED_NOT
                TriState.ENABLED_NOT -> TriState.DISABLED
            },
        )
    }

    private fun TriState.matches(value: Boolean): Boolean = when (this) {
        TriState.DISABLED -> true
        TriState.ENABLED_IS -> value
        TriState.ENABLED_NOT -> !value
    }

    private data class BadgePrefs(
        val download: Boolean,
        val unread: Boolean,
        val language: Boolean,
        val source: Boolean,
    )

    private data class NovelFilters(
        val downloaded: TriState,
        val unread: TriState,
        val started: TriState,
        val completed: TriState,
        val bookmarked: TriState,
    )

    private data class Misc(
        val defaultSort: Long,
        val randomSeed: Long,
        val showContinue: Boolean,
        val showHidden: Boolean,
        val categorySortOrder: Int,
    )

    private data class MergeSettings(
        val manualMerges: Set<String>,
        val manualUnmerges: Set<String>,
        val autoMergeSameTitle: Boolean,
        val requireAuthor: Boolean,
        val showSourceIcons: Boolean,
    )

    private data class LibrarySettings(
        val badges: BadgePrefs,
        val defaultSort: Long,
        val randomSeed: Long,
        val showContinue: Boolean,
        val showHidden: Boolean,
        val filters: NovelFilters,
        val merge: MergeSettings,
        val categorySortOrder: Int,
    )

    private fun NovelFilters.matches(n: LibraryNovel): Boolean =
        downloaded.matches(n.downloadCount > 0) &&
            unread.matches(n.unreadCount > 0) &&
            started.matches(n.hasStarted) &&
            completed.matches(n.novel.status == NovelStatusCode.COMPLETED.toLong()) &&
            bookmarked.matches(n.bookmarkCount > 0)

    private val NovelFilters.hasActive: Boolean
        get() = listOf(downloaded, unread, started, completed, bookmarked).any { it != TriState.DISABLED }

    sealed interface Dialog {
        data class ChangeCategory(val novelIds: List<Long>, val preselected: List<CheckboxState<Category>>) : Dialog
        data class Delete(val novelIds: List<Long>) : Dialog
        data class Settings(val categoryId: Long, val initialTab: Int) : Dialog
    }

    data class State(
        val isLoading: Boolean = true,
        val searchQuery: String? = null,
        val selection: Set<Long> = emptySet(),
        val collapsedCategories: Set<String> = emptySet(),
        val activeCategoryIndex: Int = 0,
        val hasActiveFilters: Boolean = false,
        val showContinueButton: Boolean = false,
        private val groupedFavorites: List<Pair<Category, List<Long>>> = emptyList(),
        private val favoritesById: Map<Long, LibraryItem> = emptyMap(),
        private val novelRoutes: Map<Long, NovelRoute> = emptyMap(),
        private val categorySortFlags: Map<Long, Long> = emptyMap(),
        private val defaultSortFlag: Long = NovelLibrarySort.default.toFlag(),
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

        /** Any selected entry is a merge group (drives the bulk Unmerge action). */
        val selectionContainsMerged: Boolean by lazy {
            selection.any { (favoritesById[it]?.relatedMangaIds?.size ?: 0) > 1 }
        }

        fun getItemsForCategory(category: Category): List<LibraryItem> =
            groupedById[category.id].orEmpty().mapNotNull { favoritesById[it] }

        fun getItemCountForCategory(category: Category): Int? = groupedById[category.id]?.size

        /** Ordered item ids (negative) for a category, for range/select-all; null id = active category. */
        fun itemIdsForCategory(categoryId: Long?): List<Long> =
            categoryId?.let { groupedById[it] }.orEmpty()

        /** Raw `NovelCategory.flags` for a category (so a sort write can preserve the hidden bit). */
        fun flagsForCategory(categoryId: Long): Long = categorySortFlags[categoryId] ?: 0L

        /** Current sort for a category, for the settings dialog's Sort tab. */
        fun sortFor(categoryId: Long): NovelLibrarySort {
            val default = NovelLibrarySort.fromFlag(defaultSortFlag)
            return if (categoryId == NovelCategory.UNCATEGORIZED_ID) {
                default
            } else {
                NovelLibrarySort.forCategory(categorySortFlags[categoryId] ?: 0L, default)
            }
        }

        /** (source, url) for the disguised item id (negative), to open the novel details screen. */
        fun routeFor(itemId: Long): NovelRoute? = novelRoutes[itemId]

        /** A random favorited novel's route (the hopper "random, global" action). */
        fun randomRoute(): NovelRoute? = novelRoutes.values.randomOrNull()

        /** A random novel route within [categoryId] (the hopper "random, in category" action). */
        fun randomRouteInCategory(categoryId: Long?): NovelRoute? =
            itemIdsForCategory(categoryId).randomOrNull()?.let { routeFor(it) }
    }

    data class NovelRoute(val source: String, val url: String)
}

private fun LibraryNovel.matchesQuery(query: String): Boolean {
    val n = novel
    return n.title.contains(query, true) ||
        (n.author?.contains(query, true) ?: false) ||
        (n.artist?.contains(query, true) ?: false) ||
        (n.genre?.any { it.contains(query, true) } ?: false)
}

/**
 * Reduce an lnreader language value to a 2-char ISO 639-1 code for the library badge. Plugins mostly
 * declare a full English name ("English", "Turkish"); reverse-map it. Values already short (a code) pass
 * through; an unmatched name falls back to its first two chars.
 */
private fun languageCodeOf(value: String): String {
    if (value.isBlank() || value.length <= 3) return value
    val match = Locale.getISOLanguages().firstOrNull {
        Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).equals(value, ignoreCase = true)
    }
    return match ?: value.take(2)
}
