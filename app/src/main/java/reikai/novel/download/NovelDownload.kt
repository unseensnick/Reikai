package reikai.novel.download

/**
 * One queued or active novel chapter download. Text-only, so there is no page model or byte progress:
 * the chip renders an indeterminate spinner while [State.QUEUE] / [State.DOWNLOADING]. Mirrors the
 * manga [eu.kanade.tachiyomi.data.download.model.Download] state machine minus everything
 * image-related. A completed download leaves the queue entirely; the on-disk file (indexed by
 * [NovelDownloadCache]) is what then signals "downloaded" to the UI.
 *
 * Only the fields the engine needs are kept: [url] for `parseChapter`, [novelId] to resolve the owning
 * source and novel, [chapterId] to look the chapter up (its name + url feed the stable-name path).
 */
data class NovelDownload(
    val novelId: Long,
    val chapterId: Long,
    val url: String,
    val state: State = State.QUEUE,
) {
    enum class State { QUEUE, DOWNLOADING, ERROR }
}
