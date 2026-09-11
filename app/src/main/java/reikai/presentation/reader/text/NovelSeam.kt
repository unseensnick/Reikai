package reikai.presentation.reader.text

import reikai.presentation.reader.NovelReaderViewModel.LoadedChapter
import tachiyomi.domain.chapter.service.calculateChapterGap

/**
 * What the marker between two chapters says, worked out once for both novel renderers: the chapter
 * that finished over the one below it, whether each is on disk, and how many chapters the numbering
 * skips between them. The three things manga's `ChapterTransition` shows, from the same inputs.
 */
data class NovelSeam(
    val finishedTitle: String,
    val finishedDownloaded: Boolean,
    val nextTitle: String,
    val nextDownloaded: Boolean,
    val missingChapters: Int,
) {
    companion object {
        fun between(finished: LoadedChapter, next: LoadedChapter) = NovelSeam(
            finishedTitle = finished.title,
            finishedDownloaded = finished.downloaded,
            nextTitle = next.title,
            nextDownloaded = next.downloaded,
            // Mihon's own count, which its transition takes from the same pair. It is negative for a
            // pair the order runs backwards, where the transition shows nothing.
            missingChapters = calculateChapterGap(next.chapterNumber, finished.chapterNumber).coerceAtLeast(0),
        )
    }
}
