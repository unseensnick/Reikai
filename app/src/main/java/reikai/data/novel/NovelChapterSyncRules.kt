package reikai.data.novel

import dev.zacsweers.metro.Inject
import reikai.domain.chapter.ChapterCopyMarkRepository
import reikai.domain.chapter.ChapterNumberOverrideRepository
import reikai.domain.chapter.LocalChapterTombstones

/**
 * The rules every novel chapter sync applies beside the source's list, as one graph object, the way the
 * manga sync's injected class holds the same three: a caller cannot leave one out.
 */
@Inject
data class NovelChapterSyncRules(
    val numberOverrides: ChapterNumberOverrideRepository,
    val copyMarks: ChapterCopyMarkRepository,
    val tombstones: LocalChapterTombstones,
)
