package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import exh.metadata.metadata.RaisedSearchMetadata

interface FollowsSource : CatalogueSource {
    suspend fun fetchFollows(page: Int): MangasPage

    /**
     * Returns every follow retrieved across all pages (for the bulk "Sync Follows to Library" action).
     */
    suspend fun fetchAllFollows(): List<Pair<SManga, RaisedSearchMetadata>>
}
