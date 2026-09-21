package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

/**
 * Lets a source sync reading and library events to its own site. Part of the contract novel APKs
 * compile against (tsundoku-otaku/extensions-lib 1.6), so every member keeps that library's exact
 * name and signature; `SourceApiContractTest` pins them.
 */
interface SourceTracker {

    val supportsChapterTracking: Boolean
        get() = true

    val supportsFavoritesTracking: Boolean
        get() = false

    /** [changedChapters] are the ones flipped to read in this batch, in any order. */
    suspend fun onChaptersRead(
        manga: SManga,
        changedChapters: List<SChapter>,
        allChapters: List<SChapter>,
        categories: List<String>,
    ) = Unit

    suspend fun onChaptersUnread(
        manga: SManga,
        changedChapters: List<SChapter>,
        allChapters: List<SChapter>,
        categories: List<String>,
    ) = Unit

    suspend fun onFavorited(
        manga: SManga,
        categories: List<String>,
    ) = Unit

    suspend fun onUnfavorited(
        manga: SManga,
        categories: List<String>,
    ) = Unit
}
