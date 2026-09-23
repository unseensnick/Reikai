package reikai.domain.track.source

import eu.kanade.domain.chapter.interactor.SetReadStatus
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.entry.EntryId
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.model.NovelChapter
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences

/**
 * What a mark read or unread hands the source's own tracker: each changed chapter with the state it
 * had, which the kernel reads to decide which chapters the site hears about.
 */
class ReadStatusHandOffConformanceTest {

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `marking read hands the tracker the chapters it changed`(side: Side) = runTest {
        val tracker = mockk<SourceTrackerDispatcher>(relaxed = true)

        side.mark(tracker, read = true, listOf(Row(1, read = false), Row(2, read = true)))

        verify { tracker.readStateWritten(true, listOf(ChapterWrite(side.entry, 1L, wasRead = false))) }
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `an unread hands the tracker each chapter with the state it had`(side: Side) = runTest {
        val tracker = mockk<SourceTrackerDispatcher>(relaxed = true)

        side.mark(tracker, read = false, listOf(Row(1, read = true), Row(2, read = false, started = true)))

        verify {
            tracker.readStateWritten(
                false,
                listOf(ChapterWrite(side.entry, 1L, wasRead = true), ChapterWrite(side.entry, 2L, wasRead = false)),
            )
        }
    }

    data class Row(val id: Long, val read: Boolean, val started: Boolean = false)

    enum class Side(val entry: EntryId) {
        MANGA(EntryId.Manga(1L)) {
            override suspend fun mark(tracker: SourceTrackerDispatcher, read: Boolean, rows: List<Row>) {
                SetReadStatus(
                    downloadPreferences = DownloadPreferences(EmittingPreferenceStore()),
                    deleteDownload = mockk(relaxed = true),
                    mangaRepository = mockk(relaxed = true),
                    chapterRepository = mockk(relaxed = true),
                    sourceTracker = tracker,
                ).await(
                    read,
                    *rows.map {
                        Chapter.create().copy(
                            id = it.id,
                            mangaId = 1L,
                            read = it.read,
                            lastPageRead = if (it.started) 5L else 0L,
                        )
                    }.toTypedArray(),
                )
            }
        },
        NOVELS(EntryId.Novel(1L)) {
            override suspend fun mark(tracker: SourceTrackerDispatcher, read: Boolean, rows: List<Row>) {
                SetNovelReadStatus(mockk(relaxed = true), mockk(relaxed = true), tracker, mockk(relaxed = true))
                    .await(
                        read = read,
                        chapters = rows.map {
                            NovelChapter(
                                id = it.id, novelId = 1L, url = "", name = "", read = it.read, bookmark = false,
                                lastTextProgress = if (it.started) 5L else 0L, chapterNumber = it.id.toDouble(),
                                sourceOrder = it.id, dateFetch = 0L, dateUpload = 0L, page = "",
                            )
                        },
                    )
            }
        },
        ;

        abstract suspend fun mark(tracker: SourceTrackerDispatcher, read: Boolean, rows: List<Row>)
    }
}
