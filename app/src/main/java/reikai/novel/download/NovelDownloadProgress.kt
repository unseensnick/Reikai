package reikai.novel.download

import reikai.data.notification.shownEntryName
import reikai.domain.novel.model.Novel

/** What the novel download notification reports: the series being downloaded, or why the drain is paused. */
sealed interface NovelDownloadProgress {

    /** [novel] is what Show entry opens; null only before the first chapter is picked. */
    data class Downloading(
        val current: Int,
        val total: Int,
        val title: String,
        val isAdult: Boolean,
        val novel: Novel? = null,
    ) : NovelDownloadProgress

    /** [reason] is a status line rather than an entry name, so no privacy switch applies to it. */
    data class Paused(val reason: String) : NovelDownloadProgress
}

fun NovelDownloadProgress.Downloading.shownText(hideAll: Boolean, hideAdult: Boolean): String? =
    shownEntryName(title, hideAll, hideAdult, isAdult)
