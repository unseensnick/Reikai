package reikai.presentation.details

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.merge.GroupMarks
import reikai.domain.novel.model.NovelChapter
import reikai.domain.reader.ChapterProgress
import tachiyomi.domain.chapter.model.Chapter

/**
 * A details row stops saying how far into a chapter reading got once the row reads as read, and the
 * read it answers to is the one the row draws: in a merged series that is the group's, so a chapter
 * finished on one source and started on the shown copy does not carry a stale progress line.
 */
class ChapterRowProgressConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a chapter read on another source of the group shows no progress line`(probe: DetailsRowProbe) {
        val row = probe.row(readHere = false, readElsewhere = true)

        row.progress shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a chapter read on its own source shows no progress line`(probe: DetailsRowProbe) {
        val row = probe.row(readHere = true, readElsewhere = false)

        row.progress shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a started unread chapter keeps its progress line`(probe: DetailsRowProbe) {
        val row = probe.row(readHere = false, readElsewhere = false)

        row.progress shouldBe probe.started
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaDetailsRowProbe, NovelDetailsRowProbe)
    }
}

/** One content type's details row for a started chapter, read here, elsewhere in the group, or neither. */
interface DetailsRowProbe {
    val started: ChapterProgress

    fun row(readHere: Boolean, readElsewhere: Boolean): EntryChapterListItem.Chapter
}

object MangaDetailsRowProbe : DetailsRowProbe {

    override fun toString() = "manga"

    override val started = ChapterProgress.Pages(lastPageRead = 5L, pageCount = 38L)

    override fun row(readHere: Boolean, readElsewhere: Boolean): EntryChapterListItem.Chapter {
        val chapter = Chapter.create().copy(id = 1L, mangaId = 1L, read = readHere, lastPageRead = 5L, pageCount = 38L)
        return ChapterList.Item(
            chapter = chapter,
            downloadState = Download.State.NOT_DOWNLOADED,
            downloadProgress = 0,
            isRead = readHere || readElsewhere,
            isBookmarked = false,
        ).toEntryChapter(sourceName = null)
    }
}

object NovelDetailsRowProbe : DetailsRowProbe {

    override fun toString() = "novel"

    override val started = ChapterProgress.Percent(4200L)

    override fun row(readHere: Boolean, readElsewhere: Boolean): EntryChapterListItem.Chapter = NovelChapter(
        id = 1L,
        novelId = 10L,
        url = "/1",
        name = "Chapter 1",
        read = readHere,
        bookmark = false,
        lastTextProgress = 4200L,
        chapterNumber = 1.0,
        sourceOrder = 1L,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    ).toEntryChapter(
        sourceName = null,
        marks = if (readElsewhere) GroupMarks(readElsewhere = setOf(1L)) else GroupMarks.NONE,
        downloadState = Download.State.NOT_DOWNLOADED,
    )
}
