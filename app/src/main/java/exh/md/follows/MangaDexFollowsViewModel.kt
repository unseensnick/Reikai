package exh.md.follows

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.all.MangaDex
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.getMainSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.browse.MangaLibraryAdder
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.repository.SourcePagingSource
import tachiyomi.domain.source.service.SourceManager

/**
 * Reuses the browse screen model but swaps the paging source for the MangaDex follow list. The
 * screen is only reachable for a MangaDex source (gated in browse), so the cast is safe.
 */
@AssistedInject
class MangaDexFollowsViewModel(
    @Assisted sourceId: Long,
    private val networkToLocalManga: NetworkToLocalManga,
    // Forwarded to the parent: an assisted subclass supplies its supertype's dependencies itself.
    sourceManager: SourceManager,
    sourcePreferences: SourcePreferences,
    libraryPreferences: LibraryPreferences,
    getRemoteManga: GetRemoteManga,
    getManga: GetManga,
    getIncognitoState: GetIncognitoState,
    reikaiSourcePreferences: ReikaiSourcePreferences,
    mangaLibraryAdder: MangaLibraryAdder,
    getFlatMetadataById: GetFlatMetadataById,
) : BrowseSourceViewModel(
    sourceId = sourceId,
    listingQuery = null,
    sourceManager = sourceManager,
    sourcePreferences = sourcePreferences,
    libraryPreferences = libraryPreferences,
    getRemoteManga = getRemoteManga,
    getManga = getManga,
    getIncognitoState = getIncognitoState,
    reikaiSourcePreferences = reikaiSourcePreferences,
    mangaLibraryAdder = mangaLibraryAdder,
    getFlatMetadataById = getFlatMetadataById,
) {

    // Its own factory: a Kotlin companion is not inherited, and the parent's would construct a plain
    // BrowseSourceViewModel and silently drop both overrides below, with no compile error.
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sourceId: Long): MangaDexFollowsViewModel
    }

    override fun createSourcePagingSource(query: String, filters: FilterList): SourcePagingSource {
        return MangaDexFollowsPagingSource(source.getMainSource<MangaDex>()!!, networkToLocalManga)
    }

    // Follows results carry their metadata inline (follow status); pass it straight through rather
    // than DB-joining like the adult-source browse does.
    override fun Flow<Manga>.combineMetadata(
        metadata: RaisedSearchMetadata?,
    ): Flow<Pair<Manga, RaisedSearchMetadata?>> {
        return map { it to metadata }
    }
}
