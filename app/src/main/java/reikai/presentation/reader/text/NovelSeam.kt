package reikai.presentation.reader.text

import reikai.domain.merge.ChapterGap
import reikai.presentation.reader.NovelReaderViewModel.LoadedChapter

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
     * This marker if a renderer draws it, else null: manga's rule for its Next transition, which always
     * shows at the end and across missing chapters, and between two consecutive ones only with "Always
     * show chapter transition" on (`WebtoonAdapter.setChapters`). A renderer not yet handed settings
     * passes null, which draws it, as the setting's default does.
     */
    fun drawn(alwaysShowTransition: Boolean?): NovelSeam? =
        takeIf { nextTitle == null || missingChapters > 0 || alwaysShowTransition != false }

    companion object {
        fun between(finished: LoadedChapter, next: LoadedChapter) = NovelSeam(
            finishedTitle = finished.title,
            finishedDownloaded = finished.downloaded,
            nextTitle = next.title,
            nextDownloaded = next.downloaded,
            // The chapter list's rule, as manga's transition takes it: two sources of a merged novel are
            // never compared, and a pair the order runs backwards is missing nothing.
            missingChapters = ChapterGap.atSeam(next.gapNeighbour(), finished.gapNeighbour(), next.gapPresent),
        )

        private fun LoadedChapter.gapNeighbour() = ChapterGap.Neighbour(chapterNumber, title, novelId)

        /**
         * How much a marker [natural] tall shrinks to fit a [page] in paged reading, as Mihon's transition page
         * fits its screen: not at all if it fits, else to just under the page. Scaled whole at a fixed width it
         * rewraps onto no more lines, so one scale always fits. reader.js `fitSeams` follows this and
         * [fitPadding]; PagedReadingTest holds both renderers to one page.
         */
        fun fitScale(natural: Float, page: Float): Float = if (natural <= page) 1f else (page - 1f) / natural

        /** The padding above and below a marker [shown] tall on a [page]: [max] while it fits, then what is left. */
        fun fitPadding(shown: Float, page: Float, max: Float): Float = ((page - shown) / 2f).coerceIn(0f, max)

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
