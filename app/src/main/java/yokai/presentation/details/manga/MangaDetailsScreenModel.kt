package yokai.presentation.details.manga

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.bookmarkedFilter
import eu.kanade.tachiyomi.data.database.models.chapterOrder
import eu.kanade.tachiyomi.data.database.models.downloadedFilter
import eu.kanade.tachiyomi.data.database.models.hideChapterTitle
import eu.kanade.tachiyomi.data.database.models.readFilter
import eu.kanade.tachiyomi.data.database.models.sortDescending
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.util.chapter.ChapterFilter
import eu.kanade.tachiyomi.util.chapter.ChapterSort
import eu.kanade.tachiyomi.util.chapter.ChapterUtil
import eu.kanade.tachiyomi.util.manga.MangaUtil
import eu.kanade.tachiyomi.util.system.launchIO
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.chapter.interactor.UpdateChapter
import yokai.domain.chapter.models.ChapterUpdate
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.manga.models.MangaUpdate
import yokai.presentation.details.detailsLog

sealed interface MangaDetailsState {
    data object Loading : MangaDetailsState
    data class Loaded(
        val manga: Manga,
        val chapters: List<Chapter>,
        /** Next chapter to read (lowest unread); null when everything is read. Drives the FAB. */
        val resumeChapter: Chapter?,
        /** Any chapter read or partially read, so the FAB reads "Resume" instead of "Start reading". */
        val hasStarted: Boolean,
        /** Effective sort key (CHAPTER_SORTING_*), resolved against global default. */
        val sorting: Int,
        val sortDescending: Boolean,
        /** Effective filters (CHAPTER_SHOW_* / SHOW_ALL), resolved against global default. */
        val readFilter: Int,
        val downloadedFilter: Int,
        val bookmarkedFilter: Int,
        val hideChapterTitles: Boolean,
        /** Every scanlator across all chapters (unfiltered), for the scanlator picker. */
        val allScanlators: Set<String>,
        val filteredScanlators: Set<String>,
        /** Whether this manga's local sort/filter matches the global default (hides set/reset buttons). */
        val sortMatchesDefault: Boolean,
        val filterMatchesDefault: Boolean,
    ) : MangaDetailsState
    data object NotFound : MangaDetailsState
}

/**
 * Phase 0-2 of the manga details Compose port: loads the manga + its displayed chapter list,
 * handles per-chapter read/bookmark writes plus mark-all, and chapter filter/sort/scanlator state.
 * Mirrors [eu.kanade.tachiyomi.ui.manga.MangaDetailsPresenter]'s load pipeline (DB read ->
 * scanlator filter -> ChapterSort) on [screenModelScope] without the presenter's runBlocking.
 * Filter/sort ops mutate the manga's chapter_flags, persist them, then reload; ChapterSort already
 * reads those flags. Tracking and download side-effects land in later phases.
 */
class MangaDetailsScreenModel(
    private val mangaId: Long,
) : StateScreenModel<MangaDetailsState>(MangaDetailsState.Loading), KoinComponent {

    private val getManga: GetManga by inject()
    private val getChapter: GetChapter by inject()
    private val updateChapter: UpdateChapter by inject()
    private val updateManga: UpdateManga by inject()
    private val chapterFilter: ChapterFilter by inject()
    private val preferences: PreferencesHelper by inject()

    init {
        reload()
    }

    private fun reload() {
        screenModelScope.launchIO {
            val manga = getManga.awaitById(mangaId)
            if (manga == null) {
                detailsLog { "manga $mangaId not found" }
                mutableState.value = MangaDetailsState.NotFound
                return@launchIO
            }
            val rawChapters = getChapter.awaitAll(manga, filterScanlators = null)
            val sort = ChapterSort(manga, chapterFilter, preferences)
            val sorted = sort.getChaptersSorted(rawChapters)
            val resume = sort.getNextUnreadChapter(rawChapters)
            val allScanlators = getChapter.awaitScanlators(mangaId)
                .filter { it.isNotBlank() }
                .toSet()
            detailsLog { "loaded \"${manga.title}\" chapters=${sorted.size} resume=${resume?.name ?: "none"}" }
            mutableState.value = MangaDetailsState.Loaded(
                manga = manga,
                chapters = sorted,
                resumeChapter = resume,
                hasStarted = rawChapters.any { it.read || it.last_page_read > 0 },
                sorting = manga.chapterOrder(preferences),
                sortDescending = manga.sortDescending(preferences),
                readFilter = manga.readFilter(preferences),
                downloadedFilter = manga.downloadedFilter(preferences),
                bookmarkedFilter = manga.bookmarkedFilter(preferences),
                hideChapterTitles = manga.hideChapterTitle(preferences),
                allScanlators = allScanlators,
                filteredScanlators = ChapterUtil.getScanlators(manga.filtered_scanlators).toSet(),
                sortMatchesDefault = sortMatchesDefault(manga),
                filterMatchesDefault = filterMatchesDefault(manga),
            )
        }
    }

    fun setRead(chapterId: Long, read: Boolean) {
        screenModelScope.launchIO {
            detailsLog { "setRead chapter=$chapterId read=$read" }
            updateChapter.await(ChapterUpdate(id = chapterId, read = read))
            reload()
        }
    }

    fun setBookmark(chapterId: Long, bookmark: Boolean) {
        screenModelScope.launchIO {
            detailsLog { "setBookmark chapter=$chapterId bookmark=$bookmark" }
            updateChapter.await(ChapterUpdate(id = chapterId, bookmark = bookmark))
            reload()
        }
    }

    fun markAllRead(read: Boolean) {
        screenModelScope.launchIO {
            val loaded = state.value as? MangaDetailsState.Loaded ?: return@launchIO
            val updates = loaded.chapters.mapNotNull { ch ->
                ch.id?.let { ChapterUpdate(id = it, read = read) }
            }
            detailsLog { "markAllRead read=$read count=${updates.size}" }
            if (updates.isNotEmpty()) updateChapter.awaitAll(updates)
            reload()
        }
    }

    fun setSortOrder(sort: Int, descend: Boolean) {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            detailsLog { "setSortOrder sort=$sort descend=$descend" }
            manga.setChapterOrder(sort, if (descend) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC)
            if (sortMatchesDefault(manga)) manga.setSortToGlobal()
            persistFlags(manga)
        }
    }

    fun setGlobalSort(sort: Int, descend: Boolean) {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            detailsLog { "setGlobalSort sort=$sort descend=$descend" }
            preferences.sortChapterOrder().set(sort)
            preferences.chaptersDescAsDefault().set(descend)
            manga.setSortToGlobal()
            persistFlags(manga)
        }
    }

    fun resetSortToDefault() {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            detailsLog { "resetSortToDefault" }
            manga.setSortToGlobal()
            persistFlags(manga)
        }
    }

    fun setFilters(read: Int, downloaded: Int, bookmarked: Int) {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            detailsLog { "setFilters read=$read downloaded=$downloaded bookmarked=$bookmarked" }
            manga.readFilter = read
            manga.downloadedFilter = downloaded
            manga.bookmarkedFilter = bookmarked
            manga.setFilterToLocal()
            if (filterMatchesDefault(manga)) manga.setFilterToGlobal()
            persistFlags(manga)
        }
    }

    fun setGlobalFilters(read: Int, downloaded: Int, bookmarked: Int) {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            detailsLog { "setGlobalFilters read=$read downloaded=$downloaded bookmarked=$bookmarked" }
            preferences.filterChapterByRead().set(read)
            preferences.filterChapterByDownloaded().set(downloaded)
            preferences.filterChapterByBookmarked().set(bookmarked)
            preferences.hideChapterTitlesByDefault().set(manga.hideChapterTitles)
            manga.setFilterToGlobal()
            persistFlags(manga)
        }
    }

    fun resetFilterToDefault() {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            detailsLog { "resetFilterToDefault" }
            manga.setFilterToGlobal()
            persistFlags(manga)
        }
    }

    fun setHideChapterTitles(hide: Boolean) {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            detailsLog { "setHideChapterTitles hide=$hide" }
            manga.displayMode = if (hide) Manga.CHAPTER_DISPLAY_NUMBER else Manga.CHAPTER_DISPLAY_NAME
            manga.setFilterToLocal()
            if (filterMatchesDefault(manga)) manga.setFilterToGlobal()
            persistFlags(manga)
        }
    }

    fun setScanlatorFilter(scanlators: Set<String>) {
        screenModelScope.launchIO {
            val manga = currentManga() ?: return@launchIO
            val all = (state.value as? MangaDetailsState.Loaded)?.allScanlators.orEmpty()
            detailsLog { "setScanlatorFilter count=${scanlators.size}/${all.size}" }
            MangaUtil.setScanlatorFilter(updateManga, manga, if (scanlators.size == all.size) emptySet() else scanlators)
            reload()
        }
    }

    private fun currentManga(): Manga? = (state.value as? MangaDetailsState.Loaded)?.manga

    private suspend fun persistFlags(manga: Manga) {
        updateManga.await(MangaUpdate(manga.id!!, chapterFlags = manga.chapter_flags))
        reload()
    }

    private fun sortMatchesDefault(manga: Manga): Boolean =
        (
            manga.sortDescending == preferences.chaptersDescAsDefault().get() &&
                manga.sorting == preferences.sortChapterOrder().get()
            ) || !manga.usesLocalSort

    private fun filterMatchesDefault(manga: Manga): Boolean =
        (
            manga.readFilter == preferences.filterChapterByRead().get() &&
                manga.downloadedFilter == preferences.filterChapterByDownloaded().get() &&
                manga.bookmarkedFilter == preferences.filterChapterByBookmarked().get() &&
                manga.hideChapterTitles == preferences.hideChapterTitlesByDefault().get()
            ) || !manga.usesLocalFilter
}
