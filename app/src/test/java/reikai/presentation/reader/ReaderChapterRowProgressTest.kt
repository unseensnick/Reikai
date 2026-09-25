package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.merge.GroupChapterFlags
import reikai.domain.novel.model.NovelChapter
import tachiyomi.domain.chapter.model.Chapter

/**
 * The chapter sheet says how far into a chapter reading got, for both types, in the unit each reader
 * counts. The wording rule itself is pinned on the shared helpers; this proves each sheet goes through
 * them, since the manga sheet once passed nothing at all.
 */
class ReaderChapterRowProgressTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a started chapter's row says how far in reading got`(probe: ChapterRowProgressProbe) {
        val row = probe.row(started = true)

        row.readProgress shouldBe probe.startedLabel
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an unstarted chapter's row carries no progress`(probe: ChapterRowProgressProbe) {
        val row = probe.row(started = false)

        row.readProgress shouldBe null
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaRowProgressProbe(), NovelRowProgressProbe())
    }
}

/** One content type's sheet row, built from a chapter either started or never opened. */
interface ChapterRowProgressProbe {
    val startedLabel: String

    fun row(started: Boolean): ReaderChapterRow
}

private fun <T> flagsOf(chapter: T, id: (T) -> Long) = GroupChapterFlags(
    pooled = listOf(chapter),
    shown = listOf(chapter),
    stitch = emptyList(),
    id = id,
    read = { false },
    bookmark = { false },
) { emptySet() }

class MangaRowProgressProbe : ChapterRowProgressProbe {

    override fun toString() = "manga"

    // Page 6 of 38: a page is stored zero-based.
    override val startedLabel = "Page: 6/38"

    override fun row(started: Boolean): ReaderChapterRow {
        val chapter = Chapter.create().copy(
            id = 1L,
            mangaId = 1L,
            lastPageRead = if (started) 5L else 0L,
            pageCount = 38L,
        )
        return ReaderChapterItem(chapter)
            .toReaderChapterRow(emptyMap(), flagsOf(chapter) { it.id }, EnglishChapterTitleWords)
    }
}

class NovelRowProgressProbe : ChapterRowProgressProbe {

    override fun toString() = "novel"

    override val startedLabel = "42%"

    override fun row(started: Boolean): ReaderChapterRow {
        val chapter = NovelChapter(
            id = 1L,
            novelId = 10L,
            url = "/1",
            name = "Chapter 1",
            read = false,
            bookmark = false,
            lastTextProgress = if (started) 4200L else 0L,
            chapterNumber = 1.0,
            sourceOrder = 1L,
            dateFetch = 0L,
            dateUpload = 0L,
            page = "",
        )
        return chapter.toReaderChapterRow(emptyMap(), emptyMap(), flagsOf(chapter) { it.id })
    }
}
