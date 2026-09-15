package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import reikai.domain.merge.ChapterGap
import reikai.domain.merge.toGapNeighbour

fun calculateChapterGap(higherReaderChapter: ReaderChapter?, lowerReaderChapter: ReaderChapter?): Int {
    // RK: the chapter list's rule, so two sources of a merged series are never compared and both
    // readers mark the same boundaries it does.
    return ChapterGap.atSeam(
        higherReaderChapter?.chapter?.toDomainChapter()?.toGapNeighbour(),
        lowerReaderChapter?.chapter?.toDomainChapter()?.toGapNeighbour(),
    )
}
