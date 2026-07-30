package reikai.presentation.library

import android.app.Application
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.manga.DownloadAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import reikai.data.novel.update.NovelUpdateJob
import reikai.domain.category.GetNovelCategories
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.presentation.library.novels.NovelLibraryScreenModel
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy

/**
 * Adapts the Reikai [NovelLibraryScreenModel] to the neutral [LibraryBehavior], the novel twin of
 * [MangaLibraryAdapter]. Maps the novel state into [LibraryScreenState] and reconciles the per-type action
 * shapes here (a neutral [EntryId] set narrows to the novel model's raw ids; the manga side's split default
 * / dynamic collapse toggles both route to the novel model's single one), never in the model.
 */
class NovelLibraryAdapter(
    private val model: NovelLibraryScreenModel,
) : LibraryProvider {

    // Lazy, so constructing the adapter in a composable never touches the DI container.
    private val getNovelCategories: GetNovelCategories by injectLazy()
    private val context: Application by injectLazy()

    override val contentType = ContentType.NOVELS

    // `by lazy` for the same reason as the injections above: nothing here is resolved until a sheet asks.
    override val settings: LibrarySettingsBinding by lazy {
        LibrarySettingsBinding(
            // Manga's axis order, so one sheet reads the same on both content types. Novels have no
            // interval-custom axis and simply omit it.
            filterAxes = MutableStateFlow(
                listOf(
                    LibraryFilterAxis(MR.strings.label_downloaded, model.filterDownloaded, true),
                    LibraryFilterAxis(MR.strings.action_filter_unread, model.filterUnread),
                    LibraryFilterAxis(MR.strings.label_started, model.filterStarted),
                    LibraryFilterAxis(MR.strings.action_filter_bookmarked, model.filterBookmarked),
                    LibraryFilterAxis(MR.strings.completed, model.filterCompleted),
                    LibraryFilterAxis(MR.strings.lewd, model.filterLewd),
                ),
            ),
            trackerFilter = model::novelFilterTracking,
            categoryFilter = LibraryCategoryFilter(
                enabled = model.filterCategoriesEnabled,
                included = model.filterCategoriesInclude,
                excluded = model.filterCategoriesExclude,
            ),
            categories = model.filterPickerCategories,
            groupMode = model.groupLibraryBy,
            // The novel global sort is the sort of the uncategorized scope, which is what the model's own
            // lookup returns for that id, so nothing has to reach past it for the preference.
            globalSort = model.state
                .map { it.sortFor(Category.UNCATEGORIZED_ID) }
                .stateIn(
                    model.screenModelScope,
                    SharingStarted.Eagerly,
                    model.state.value.sortFor(Category.UNCATEGORIZED_ID),
                ),
            setSort = { categoryId, type, direction ->
                model.setSort(
                    categoryId ?: Category.UNCATEGORIZED_ID,
                    type,
                    direction == LibrarySort.Direction.Ascending,
                )
            },
            resetSort = model::resetSort,
        )
    }

    override val state: StateFlow<LibraryScreenState> =
        model.state
            .map { it.toNeutral() }
            .stateIn(model.screenModelScope, SharingStarted.Eagerly, model.state.value.toNeutral())

    private fun NovelLibraryScreenModel.State.toNeutral() = LibraryScreenState(
        categories = displayedCategories,
        isLoading = isLoading,
        isLibraryEmpty = isLibraryEmpty,
        searchQuery = searchQuery,
        hasActiveFilters = hasActiveFilters,
        collapsedCategories = collapsedCategories,
        // Novels keep one collapsed set for both real and dynamic categories.
        collapsedDynamicCategories = collapsedCategories,
        coercedActiveCategoryIndex = coercedActiveCategoryIndex,
        showContinueButton = showContinueButton,
        itemsForCategory = this::getItemsForCategory,
        itemCountForCategory = this::getItemCountForCategory,
    )

    override fun search(query: String?) {
        model.search(query)
    }

    override fun refresh(category: Category?) = NovelUpdateJob.startNow(context, category)

    override fun randomEntry(categoryId: Long?): EntryId? =
        model.state.value.randomItemId(categoryId)?.let(EntryId::Novel)

    override fun toggleDefaultCategoryCollapse(headerKey: String) {
        model.toggleCategoryCollapse(headerKey)
    }
    override fun toggleDynamicCategoryCollapse(headerKey: String) {
        model.toggleCategoryCollapse(headerKey)
    }
    override fun toggleAllCategoriesCollapsed(
        categories: List<Category>,
    ) {
        model.toggleAllCategoriesCollapsed(categories)
    }

    // Each verb takes the neutral selection and hands the model only the raw ids of its own content
    // type, so a mixed selection never reaches a provider that cannot act on it.
    private fun Set<EntryId>.ownIds() = filterIsInstance<EntryId.Novel>().map { it.rawId }

    override fun markReadSelection(entries: Set<EntryId>, read: Boolean) {
        model.markReadSelection(entries.ownIds(), read)
    }
    override fun performDownloadAction(entries: Set<EntryId>, action: DownloadAction) {
        model.performDownloadAction(entries.ownIds(), action)
    }
    override fun mergeSelection(entries: Set<EntryId>) {
        model.mergeSelection(entries.ownIds())
    }
    override fun unmergeSelection(entries: Set<EntryId>) {
        model.unmergeSelection(entries.ownIds())
    }

    // The category write applies to exactly the ids it is handed, so the merge group is expanded here;
    // delete expands on its own, but only when asked to.
    override fun setCategories(
        entries: Set<EntryId>,
        addCategories: List<Long>,
        removeCategories: List<Long>,
    ) {
        model.setNovelCategories(
            model.state.value.memberIdsFor(entries.ownIds()),
            addCategories,
            removeCategories,
        )
    }

    override fun deleteEntries(
        entries: Set<EntryId>,
        deleteFromLibrary: Boolean,
        deleteDownloads: Boolean,
        removeGroupedSources: Boolean,
    ) {
        model.removeNovels(entries.ownIds(), deleteFromLibrary, deleteDownloads, removeGroupedSources)
    }

    override fun containsMerged(entries: Set<EntryId>) =
        model.state.value.containsMerged(entries.ownIds())

    /** Novels have no local-source concept, so Download always applies and nothing is ever local. */
    override fun canDownload(entries: Set<EntryId>) = true
    override fun containsLocal(entries: Set<EntryId>) = false

    override fun groupedSourceCount(entries: Set<EntryId>): Int {
        val ids = entries.ownIds()
        val state = model.state.value
        return if (state.containsMerged(ids)) state.memberIdsFor(ids).size else 0
    }

    override suspend fun assignableCategories() =
        getNovelCategories.await().filterNot { it.isSystemCategory }

    override suspend fun categoryIdsFor(entries: Set<EntryId>): List<Set<Long>> =
        model.state.value.memberIdsFor(entries.ownIds())
            .map { id -> getNovelCategories.awaitByNovelId(id).mapTo(mutableSetOf()) { it.id } }

    override fun updateActiveCategoryIndex(index: Int) {
        model.updateActiveCategoryIndex(index)
    }
}
