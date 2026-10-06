package eu.kanade.presentation.more.settings.screen.novel

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import reikai.domain.novel.NovelPreferences
import reikai.novel.content.NovelCodeSnippet
import reikai.novel.content.NovelSnippetKind
import reikai.novel.content.NovelSnippets
import reikai.novel.content.NovelStoredToggleList

/** One kind of snippet, CSS or JavaScript, each its own list in its own preference. */
@AssistedInject
class NovelCodeSnippetsViewModel(
    @Assisted kind: NovelSnippetKind,
    novelPreferences: NovelPreferences,
) : NovelToggleListViewModel<NovelCodeSnippet>(
    NovelStoredToggleList(novelPreferences.readerSnippets(kind), NovelSnippets),
) {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(kind: NovelSnippetKind): NovelCodeSnippetsViewModel
    }
}
