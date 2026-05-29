package yokai.presentation.details.manga

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.util.chapter.ChapterFilter
import eu.kanade.tachiyomi.util.chapter.ChapterSort
import eu.kanade.tachiyomi.util.system.launchIO
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.chapter.interactor.UpdateChapter
import yokai.domain.chapter.models.ChapterUpdate
import yokai.domain.manga.interactor.GetManga
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
    ) : MangaDetailsState
    data object NotFound : MangaDetailsState
}

/**
 * Phase 0-1 of the manga details Compose port: loads the manga + its displayed chapter list, and
 * handles per-chapter read/bookmark writes plus mark-all. Mirrors
 * [eu.kanade.tachiyomi.ui.manga.MangaDetailsPresenter]'s load pipeline (DB read -> scanlator
 * filter -> ChapterSort) on [screenModelScope] without the presenter's runBlocking. Tracking and
 * download side-effects of marking read land in later phases.
 */
class MangaDetailsScreenModel(
    private val mangaId: Long,
) : StateScreenModel<MangaDetailsState>(MangaDetailsState.Loading), KoinComponent {

    private val getManga: GetManga by inject()
    private val getChapter: GetChapter by inject()
    private val updateChapter: UpdateChapter by inject()
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
            detailsLog { "loaded \"${manga.title}\" chapters=${sorted.size} resume=${resume?.name ?: "none"}" }
            mutableState.value = MangaDetailsState.Loaded(
                manga = manga,
                chapters = sorted,
                resumeChapter = resume,
                hasStarted = rawChapters.any { it.read || it.last_page_read > 0 },
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
}
