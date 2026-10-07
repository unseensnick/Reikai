package exh.source

import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import okhttp3.Request
import okhttp3.Response

/**
 * A gallery wrapper's update: the extension's own details and chapters, with this source's metadata
 * laid over the details by [parseOver]. Starting from the extension keeps what the metadata does not
 * carry, its description, status and update strategy among them, so a finished gallery the extension
 * fetches once is not refetched by every library update.
 */
suspend fun DelegatedHttpSource.layeredMangaUpdate(
    manga: SManga,
    chapters: List<SChapter>,
    fetchDetails: Boolean,
    fetchChapters: Boolean,
    detailsRequest: suspend (SManga) -> Request = { mangaDetailsRequest(it) },
    parseOver: suspend (base: SManga, response: Response) -> SManga,
): SMangaUpdate {
    val own = delegate.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
    if (!fetchDetails) return own
    // A details parse may leave the url and title unset, and the metadata keys its row on the url
    // and copies the title, so both fall back to the stored entry.
    val base = own.manga.also {
        it.url = manga.url
        try {
            it.title
        } catch (_: UninitializedPropertyAccessException) {
            it.title = manga.title
        }
    }
    val response = client.newCall(detailsRequest(manga)).awaitSuccess()
    return SMangaUpdate(parseOver(base, response), own.chapters)
}
