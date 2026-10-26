package reikai.presentation.reader.text

import reikai.domain.reader.ChapterProgress
import reikai.presentation.reader.ReaderResume

/** Where a novel chapter opens, shared by both renderers, on the rule [ReaderResume] keeps for both types. */
object NovelResume {

    /** [lastTextProgress] is the stored 0..10000, hundredths of a percent, which both renderers resume at. */
    fun progress(read: Boolean, lastTextProgress: Long, preserveOnRead: Boolean): ChapterProgress.Percent =
        if (ReaderResume.keepsPosition(read, preserveOnRead)) {
            ChapterProgress.Percent(lastTextProgress.coerceIn(0L, ChapterProgress.Percent.FULL))
        } else {
            ChapterProgress.Percent(0)
        }
}
