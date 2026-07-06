package exh.md.follows

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.all.MangaDex
import tachiyomi.data.source.BaseSourcePagingSource

/**
 * Pages the signed-in user's MangaDex follow list (offset-based, see [MangaDex.fetchFollows]).
 */
class MangaDexFollowsPagingSource(private val mangadex: MangaDex) : BaseSourcePagingSource(mangadex) {

    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return mangadex.fetchFollows(currentPage)
    }
}
