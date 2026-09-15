package reikai.presentation.reader.text

import reikai.presentation.reader.ReaderResume

/** Where a novel chapter opens, shared by both renderers, on the rule [ReaderResume] keeps for both types. */
object NovelResume {

    /** [lastTextProgress] is the stored 0..10000, hundredths of a percent; the result is 0..100. */
    fun percent(read: Boolean, lastTextProgress: Long, preserveOnRead: Boolean): Int =
        if (ReaderResume.keepsPosition(read, preserveOnRead)) (lastTextProgress / 100).coerceIn(0L, 100L).toInt() else 0
}
