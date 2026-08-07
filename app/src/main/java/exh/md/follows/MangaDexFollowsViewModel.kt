package exh.md.follows

import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.all.MangaDex
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.getMainSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.repository.SourcePagingSource

/**
 * Reuses the browse screen model but swaps the paging source for the MangaDex follow list. The
 * screen is only reachable for a MangaDex source (gated in browse), so the cast is safe.
 */
class MangaDexFollowsViewModel(sourceId: Long) : BrowseSourceViewModel(sourceId, null) {

    // Its own key and factory: a Kotlin companion is not inherited, so leaning on the parent's would
    // construct a plain BrowseSourceViewModel and silently drop both overrides below, with no
    // compile error.
    companion object {
        val SOURCE_ID_KEY = CreationExtras.Key<Long>()

        val Factory = viewModelFactory {
            initializer { MangaDexFollowsViewModel(sourceId = get(SOURCE_ID_KEY)!!) }
        }
    }

    override fun createSourcePagingSource(query: String, filters: FilterList): SourcePagingSource {
        return MangaDexFollowsPagingSource(source.getMainSource<MangaDex>()!!)
    }

    // Follows results carry their metadata inline (follow status); pass it straight through rather
    // than DB-joining like the adult-source browse does.
    override fun Flow<Manga>.combineMetadata(
        metadata: RaisedSearchMetadata?,
    ): Flow<Pair<Manga, RaisedSearchMetadata?>> {
        return map { it to metadata }
    }
}
