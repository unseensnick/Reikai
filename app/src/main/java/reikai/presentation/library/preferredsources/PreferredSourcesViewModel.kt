package reikai.presentation.library.preferredsources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.novel.source.NovelSourceManager
import reikai.util.runCatchingCancellable
import tachiyomi.domain.source.service.SourceManager

/**
 * The preferred-sources rankings, highest priority first, one [SourceRankingEditor] per content type.
 * [reikai.domain.manga.MangaGroupStitcher] and [reikai.domain.novel.NovelGroupStitcher] read them to
 * pick the trunk of a merged chapter list, falling back to most chapters when a ranking is empty.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class PreferredSourcesViewModel(
    sourceManager: SourceManager,
    novelSourceManager: NovelSourceManager,
    preferences: ReikaiLibraryPreferences,
) : ViewModel() {

    val manga = SourceRankingEditor(
        scope = viewModelScope,
        sources = sourceManager.sources.map { sources ->
            sources.map { PreferredSourceItem(it.id.toString(), it.name, it.lang) }
        },
        ranking = preferences.preferredMangaSources,
        parseKey = String::toLongOrNull,
    )

    val novels = SourceRankingEditor(
        scope = viewModelScope,
        sources = flow {
            runCatchingCancellable { novelSourceManager.ensureLoaded() }
            emitAll(
                novelSourceManager.sources.map { sources ->
                    sources.map { PreferredSourceItem(it.id, it.name, it.lang) }
                },
            )
        },
        ranking = preferences.preferredNovelSources,
        parseKey = { it },
    )
}
