package eu.kanade.tachiyomi.ui.deeplink

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.ResolvableSource
import eu.kanade.tachiyomi.source.online.UriType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import reikai.domain.source.NovelLinkTarget
import reikai.domain.source.ResolveMangaLink
import reikai.domain.source.ResolveNovelLink
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.chapter.interactor.GetChapterByUrlAndMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

@AssistedInject
class DeepLinkViewModel(
    @Assisted query: String,
    private val sourceManager: SourceManager,
    private val networkToLocalManga: NetworkToLocalManga,
    private val getChapterByUrlAndMangaId: GetChapterByUrlAndMangaId,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    // RK -->
    private val resolveMangaLink: ResolveMangaLink,
    private val resolveNovelLink: ResolveNovelLink,
    // RK <--
) : ViewModel() {

    val state: StateFlow<DeepLinkViewModel.State>
        field = MutableStateFlow<DeepLinkViewModel.State>(State.Loading)

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(query: String): DeepLinkViewModel
    }

    init {
        viewModelScope.launchIO {
            // RK --> link hooks, then stored rows, then checked guesses, manga before novel within each
            state.value = upstreamHook(query)
                ?: resolveMangaLink.bySearch(query)?.let { State.Result(it) }
                ?: resolveNovelLink.byHook(query)?.toState()
                ?: resolveMangaLink.byStoredRow(query)?.let { State.Result(it) }
                ?: resolveNovelLink.byStoredRow(query)?.toState()
                ?: resolveMangaLink.byGuess(query)?.let { State.Result(it) }
                ?: resolveNovelLink.byGuess(query)?.toState()
                ?: State.NoResults
        }
    }

    private fun NovelLinkTarget.toState(): State = when (this) {
        is NovelLinkTarget.Novel -> State.NovelResult(sourceId, url)
        is NovelLinkTarget.Chapter -> State.NovelChapterResult(novelId, chapterId)
    }

    private suspend fun upstreamHook(query: String): State? {
        run {
            // RK <--
            val source = sourceManager.getAll()
                .filterIsInstance<ResolvableSource>()
                .firstOrNull { it.getUriType(query) != UriType.Unknown }

            val manga = source?.getManga(query)?.let {
                networkToLocalManga(it.toDomainManga(source.id))
            }

            val chapter = if (source?.getUriType(query) == UriType.Chapter && manga != null) {
                source.getChapter(query)?.let { getChapterFromSChapter(it, manga, source) }
            } else {
                null
            }

            // RK --> a miss falls through to the next tier instead of ending the search
            return if (manga == null) {
                null
            } else {
                if (chapter == null) {
                    State.Result(manga)
                } else {
                    State.Result(manga, chapter.id)
                }
            }
        }
        // RK <--
    }

    private suspend fun getChapterFromSChapter(sChapter: SChapter, manga: Manga, source: Source): Chapter? {
        val localChapter = getChapterByUrlAndMangaId.await(sChapter.url, manga.id)

        if (localChapter != null) return localChapter
        updateMangaFromRemote(manga, fetchChapters = true).getOrElse { return null }
        return getChapterByUrlAndMangaId.await(sChapter.url, manga.id)
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data object NoResults : State

        @Immutable
        data class Result(val manga: Manga, val chapterId: Long? = null) : State

        // RK -->
        @Immutable
        data class NovelResult(val sourceId: String, val url: String) : State

        @Immutable
        data class NovelChapterResult(val novelId: Long, val chapterId: Long) : State
        // RK <--
    }
}
