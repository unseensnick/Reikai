package exh.source

import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
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
    parseOver: suspend (base: SManga, response: Response) -> SManga,
): SMangaUpdate {
    val own = delegate.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
    if (!fetchDetails) return own
    // A details parse leaves the url unset, and the metadata keys its row on it.
    val base = own.manga.also { it.url = manga.url }
    val response = client.newCall(mangaDetailsRequest(manga)).awaitSuccess()
    return SMangaUpdate(parseOver(base, response), own.chapters)
}
