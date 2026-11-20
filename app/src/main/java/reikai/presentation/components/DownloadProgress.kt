package reikai.presentation.components

/**
 * How far a chapter's download has got, where the engine behind it can say, for the details list and Recents
 * alike. A typed slot rather than a number, because the novel downloader tracks no per-chapter progress and a
 * zero there is indistinguishable from a download that has genuinely made no progress.
 */
sealed interface DownloadProgress {
    data class Live(val percent: () -> Int) : DownloadProgress

    /** The novel downloader, which tracks no per-chapter progress. */
    data object Unsupported : DownloadProgress
}

/** An engine that cannot report progress draws none rather than a zero that reads as a stall. */
fun DownloadProgress.asProvider(): () -> Int = when (this) {
    is DownloadProgress.Live -> percent
    DownloadProgress.Unsupported -> NO_PROGRESS
}

val NO_PROGRESS: () -> Int = { 0 }
