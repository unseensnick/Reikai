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
    /** Null below the novel's last chapter, where the marker says there is no next one. */
    val nextTitle: String?,
    val nextDownloaded: Boolean,
    val missingChapters: Int,
) {
    /**
     * Whether a renderer draws this marker: manga's rule for its Next transition, which always shows
     * at the end and across missing chapters, and between two consecutive ones only with "Always show
     * chapter transition" on (`WebtoonAdapter.setChapters`).
     */
    fun isShown(alwaysShowTransition: Boolean): Boolean =
        nextTitle == null || missingChapters > 0 || alwaysShowTransition

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

        /** The marker below [chapter] when nothing follows it, "There's no next chapter" under its
         *  name, or null while a chapter does. */
        fun end(chapter: LoadedChapter): NovelSeam? {
            if (!chapter.isLast) return null
            return NovelSeam(
                finishedTitle = chapter.title,
                finishedDownloaded = chapter.downloaded,
                nextTitle = null,
                nextDownloaded = false,
                missingChapters = 0,
            )
        }
    }
}
