package reikai.novel.download

import reikai.data.notification.shownEntryName

/** What the novel download notification reports: the series being downloaded, or why the drain is paused. */
sealed interface NovelDownloadProgress {
    val current: Int
    val total: Int

    data class Downloading(
        override val current: Int,
        override val total: Int,
        val title: String,
        val isAdult: Boolean,
    ) : NovelDownloadProgress

    /** [reason] is a status line rather than an entry name, so no privacy switch applies to it. */
    data class Paused(override val current: Int, override val total: Int, val reason: String) : NovelDownloadProgress
}

fun NovelDownloadProgress.shownText(hideAll: Boolean, hideAdult: Boolean): String? = when (this) {
    is NovelDownloadProgress.Downloading -> shownEntryName(title, hideAll, hideAdult, isAdult)
    is NovelDownloadProgress.Paused -> reason
}
