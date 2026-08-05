package reikai.novel.download

/**
 * One queued or active novel chapter download. Text-only, so there is no page model or byte progress
 * and the chip renders an indeterminate spinner while queued or downloading. Mirrors the manga
 * [eu.kanade.tachiyomi.data.download.model.Download] state machine minus everything image-related. A
 * completed download leaves the queue entirely, and the on-disk file indexed by [NovelDownloadCache]
 * is what then signals "downloaded" to the UI. Only the fields the engine needs are kept: [url] for
 * `parseChapter`, [novelId] to resolve the owning source, [chapterId] to look the chapter up.
 */
data class NovelDownload(
    val novelId: Long,
    val chapterId: Long,
    val url: String,
    val state: State = State.QUEUE,
) {
    enum class State { QUEUE, DOWNLOADING, ERROR }
}
