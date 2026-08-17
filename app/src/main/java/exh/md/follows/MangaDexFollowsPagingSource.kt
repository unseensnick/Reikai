package exh.md.follows

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.all.MangaDex
import tachiyomi.data.source.BaseSourcePagingSource
import tachiyomi.domain.manga.interactor.NetworkToLocalManga

/**
 * Pages the signed-in user's MangaDex follow list (offset-based, see [MangaDex.fetchFollows]).
 */
class MangaDexFollowsPagingSource(
    private val mangadex: MangaDex,
    networkToLocalManga: NetworkToLocalManga,
) : BaseSourcePagingSource(mangadex, networkToLocalManga) {

    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return mangadex.fetchFollows(currentPage)
    }
}
