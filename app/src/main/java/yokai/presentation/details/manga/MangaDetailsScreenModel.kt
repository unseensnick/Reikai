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
import yokai.domain.manga.interactor.GetManga

sealed interface MangaDetailsState {
    data object Loading : MangaDetailsState
    data class Loaded(val manga: Manga, val chapters: List<Chapter>) : MangaDetailsState
    data object NotFound : MangaDetailsState
}

/**
 * Phase 0 of the manga details Compose port: read-only load of the manga + its displayed chapter
 * list. Mirrors [eu.kanade.tachiyomi.ui.manga.MangaDetailsPresenter]'s load pipeline (DB read ->
 * scanlator filter -> ChapterSort) but on [screenModelScope] without the presenter's runBlocking.
 */
class MangaDetailsScreenModel(
    private val mangaId: Long,
) : StateScreenModel<MangaDetailsState>(MangaDetailsState.Loading), KoinComponent {

    private val getManga: GetManga by inject()
    private val getChapter: GetChapter by inject()
    private val chapterFilter: ChapterFilter by inject()
    private val preferences: PreferencesHelper by inject()

    init {
        screenModelScope.launchIO {
            val manga = getManga.awaitById(mangaId)
            if (manga == null) {
                mutableState.value = MangaDetailsState.NotFound
                return@launchIO
            }
            val rawChapters = getChapter.awaitAll(manga, filterScanlators = null)
            val sorted = ChapterSort(manga, chapterFilter, preferences).getChaptersSorted(rawChapters)
            mutableState.value = MangaDetailsState.Loaded(manga, sorted)
        }
    }
}
