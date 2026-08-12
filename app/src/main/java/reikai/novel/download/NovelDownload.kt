package reikai.novel.download

import eu.kanade.tachiyomi.data.download.model.Download

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

/**
 * The queue state in the terms every download control speaks, which are the manga engine's. There is
 * no DOWNLOADED case because a finished download leaves the queue; the disk index answers that.
 */
fun NovelDownload.State.toDownloadState(): Download.State = when (this) {
    NovelDownload.State.QUEUE -> Download.State.QUEUE
    NovelDownload.State.DOWNLOADING -> Download.State.DOWNLOADING
    NovelDownload.State.ERROR -> Download.State.ERROR
}
