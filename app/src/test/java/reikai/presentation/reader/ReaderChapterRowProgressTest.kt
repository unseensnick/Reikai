package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.merge.DownloadTargets
import reikai.domain.merge.GroupChapterFlags
import reikai.domain.merge.MergeScope
import reikai.domain.novel.model.NovelChapter
import reikai.domain.reader.ChapterProgress
import tachiyomi.domain.chapter.model.Chapter
import kotlin.time.Instant

/**
 * The chapter sheet carries how far into a chapter reading got, for both types, in the unit each reader
 * counts, and titles its rows the way the details list does. The wording is readProgressLabel's, pinned on
 * its own helpers; this proves each sheet hands it the stored progress.
 */
class ReaderChapterRowProgressTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a started chapter's row says how far in reading got`(probe: ChapterRowProgressProbe) {
        val row = probe.row(started = true)

        row.progress shouldBe probe.startedProgress
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an unstarted chapter's row reads as not started`(probe: ChapterRowProgressProbe) {
        val row = probe.row(started = false)

        row.progress?.hasStarted shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry shown by chapter number titles its rows by number`(probe: ChapterRowProgressProbe) {
        val row = probe.row(started = false, numberOnly = true)

        row.title shouldBe "Chapter 3"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry shown by name titles its rows by name`(probe: ChapterRowProgressProbe) {
        val row = probe.row(started = false, numberOnly = false)

        row.title shouldBe "The Duel"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a chapter the source numbered nothing keeps its name under number display`(probe: ChapterRowProgressProbe) {
        val row = probe.row(started = false, numberOnly = true, number = -1.0)

        row.title shouldBe "The Duel"
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaRowProgressProbe(), NovelRowProgressProbe())
    }
}

/** One content type's sheet row, built from a chapter either started or never opened. */
interface ChapterRowProgressProbe {
    val startedProgress: ChapterProgress

    fun row(started: Boolean, numberOnly: Boolean = false, number: Double = 3.0): ReaderChapterRow
}

private fun <T> flagsOf(chapter: T, id: (T) -> Long) = GroupChapterFlags(
    scope = MergeScope.Group,
    pooled = listOf(chapter),
    shown = listOf(chapter),
    stitch = emptyList(),
    id = id,
    read = { false },
    bookmark = { false },
) { emptySet() }

class MangaRowProgressProbe : ChapterRowProgressProbe {

    override fun toString() = "manga"

    override val startedProgress = ChapterProgress.Pages(lastPageRead = 5, pageCount = 38)

    override fun row(started: Boolean, numberOnly: Boolean, number: Double): ReaderChapterRow {
        val chapter = Chapter.create().copy(
            id = 1L,
            mangaId = 1L,
            name = "The Duel",
            chapterNumber = number,
            lastPageRead = if (started) 5 else 0,
            pageCount = 38,
        )
        return ReaderChapterItem(chapter)
            .toReaderChapterRow(
                emptyMap(),
                flagsOf(chapter) { it.id },
                numberOnly,
                EnglishChapterTitleWords,
                DownloadTargets.OWN,
            )
    }
}

class NovelRowProgressProbe : ChapterRowProgressProbe {

    override fun toString() = "novel"

    override val startedProgress = ChapterProgress.Percent(4200L)

    override fun row(started: Boolean, numberOnly: Boolean, number: Double): ReaderChapterRow {
        val chapter = NovelChapter(
            id = 1L,
            novelId = 10L,
            url = "/1",
            name = "The Duel",
            read = false,
            bookmark = false,
            lastTextProgress = if (started) 4200L else 0L,
            chapterNumber = number,
            sourceOrder = 1L,
            dateFetch = Instant.fromEpochMilliseconds(0L),
            dateUpload = null,
            page = "",
        )
        return chapter.toReaderChapterRow(
            emptyMap(),
            emptyMap(),
            flagsOf(chapter) { it.id },
            numberOnly,
            EnglishChapterTitleWords,
            DownloadTargets.OWN,
        )
    }
}
