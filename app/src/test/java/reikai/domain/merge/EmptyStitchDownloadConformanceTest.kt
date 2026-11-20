package reikai.domain.merge

import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelChapterSettings
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.interactor.NovelGroupChapters
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.presentation.reader.EnglishChapterTitleWords
import reikai.presentation.reader.toReaderChapterRow
import reikai.presentation.recents.fetchedCopies
import reikai.presentation.recents.recentsCopiesDownloadUi
import reikai.presentation.recents.soloUnit
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

/**
 * A merged group with no stored stitch: entries 1 and 2, chapter 10 on entry 1 and 20 on entry 2. Each
 * caller asks DownloadTargets.of, so chapter 10 downloads its own copy while entry 1's source is
 * installed and nothing once it is gone: no control drawn, nothing queued in bulk. Each case runs the
 * function its caller builds targets with: the libraries' and the manga reader's group builds, the novel
 * reader's interactor and Recents' entry points. Details' All views call the kernel directly.
 */
class EmptyStitchDownloadConformanceTest {

    @ParameterizedTest(name = "{0}, own source installed: {1}")
    @MethodSource("cases")
    fun `a row with no stored stitch downloads its own copy only while its source is installed`(
        caller: Caller,
        ownInstalled: Boolean,
        expected: List<Long>,
    ) = runTest {
        caller.fetched(ownInstalled) shouldBe expected
    }

    enum class Caller {
        /** LibraryViewModel.enqueueDownloads, queueing in bulk. */
        MANGA_LIBRARY {
            override suspend fun fetched(ownInstalled: Boolean): List<Long> {
                val pooled = listOf(manga(10, 1), manga(20, 2))
                return mangaGroup(pooled).downloadTargets(MergeScope.Group, pooled.take(1)) {
                    it.id == 2L || ownInstalled
                }.of(pooled.take(1), pooled) { it.id }.map { it.id }
            }
        },

        /** NovelLibraryViewModel.performDownloadAction, queueing in bulk. */
        NOVEL_LIBRARY {
            override suspend fun fetched(ownInstalled: Boolean): List<Long> {
                val pooled = listOf(novel(10, 1), novel(20, 2))
                val owners = listOf(1L, 2L).associateWith { Novel.create().copy(id = it) }
                return NovelGroupChapters(pooled.take(1), emptyList(), pooled).downloadTargets(owners) {
                    it.id == 2L || ownInstalled
                }.of(pooled.take(1), pooled) { it.id }.map { it.id }
            }
        },

        /** ReaderViewModel's targets, as the chapter sheet draws chapter 10's control. */
        MANGA_READER_SHEET {
            override suspend fun fetched(ownInstalled: Boolean): List<Long> {
                val pooled = listOf(manga(10, 1), manga(20, 2))
                val targets = mangaGroup(pooled).downloadTargets(MergeScope.Group, pooled) {
                    it.id == 2L || ownInstalled
                }
                val row = ReaderChapterItem(pooled[0])
                    .toReaderChapterRow(emptyMap(), noFlags(pooled) { it.id }, false, EnglishChapterTitleWords, targets)
                return listOf(10L).filter { row.offersDownload }
            }
        },

        /** GetNextNovelChapter.readingRows, whose targets the novel reader's bulk downloads read. */
        NOVEL_READER {
            override suspend fun fetched(ownInstalled: Boolean): List<Long> {
                val reading = novelReader().readingRows(1L, sourceScoped = false, isInstalled = {
                    it.id == 2L || ownInstalled
                }) { _, _ -> emptySet() }
                return reading.downloadTargets.of(reading.pooled.filter { it.id == 10L }, reading.pooled) { it.id }
                    .map { it.id }
            }
        },

        /** The novel reader opened on one source, through the same interactor. */
        NOVEL_READER_SOURCE_SCOPE {
            override suspend fun fetched(ownInstalled: Boolean): List<Long> {
                val reading = novelReader().readingRows(1L, sourceScoped = true, isInstalled = {
                    it.id == 2L || ownInstalled
                }) { _, _ -> emptySet() }
                return reading.downloadTargets.of(reading.rows, reading.rows) { it.id }.map { it.id }
            }
        },

        /** Recents' Download action on a selected row. */
        RECENTS_ACTIONS {
            override suspend fun fetched(ownInstalled: Boolean): List<Long> = fetchedCopies(
                rows = listOf(novel(10, 1)),
                scope = MergeScope.Group,
                id = { it.id },
                ownerOf = { it.novelId },
                stitchOf = { emptyList() },
                chaptersOf = { emptyList() },
                installedAmong = { rows -> if (ownInstalled) rows.mapTo(HashSet()) { it.id } else emptySet() },
            ).map { it.id }
        },

        /** A Recents row's control, whose copies fall back to the chapter alone ([soloUnit]). */
        RECENTS_ROW {
            override suspend fun fetched(ownInstalled: Boolean): List<Long> {
                val ui = recentsCopiesDownloadUi(
                    10L,
                    { listOf(ChapterCopyRow(10L, soloUnit(10L), "t", "1", "c", null, "/10")) },
                    queued = { null },
                    progress = null,
                    isInstalled = { ownInstalled },
                ) { false }
                return listOf(10L).filter { ui.offered() }
            }
        },
        ;

        abstract suspend fun fetched(ownInstalled: Boolean): List<Long>
    }

    companion object {
        @JvmStatic
        fun cases() = Caller.entries.flatMap {
            listOf(Arguments.of(it, true, listOf(10L)), Arguments.of(it, false, emptyList<Long>()))
        }
    }
}

private fun manga(id: Long, mangaId: Long) = Chapter.create().copy(id = id, mangaId = mangaId, name = "c$id")

/** Manga 1 and 2 as one group with no stored stitch. */
private fun mangaGroup(pooled: List<Chapter>) = MergedChapterProvider.Group(
    mangaById = listOf(1L, 2L).associateWith { Manga.create().copy(id = it) },
    chapters = pooled,
    sourceNameByMangaId = emptyMap(),
    stitch = emptyList(),
    pooledChapters = pooled,
)

private fun novel(id: Long, novelId: Long) = NovelChapter(
    id = id,
    novelId = novelId,
    url = "u$id",
    name = "Ch 1",
    read = false,
    bookmark = false,
    lastTextProgress = 0L,
    chapterNumber = 1.0,
    sourceOrder = 1L,
    dateFetch = Instant.fromEpochMilliseconds(0L),
    dateUpload = null,
    page = "",
)

private fun <T> noFlags(pooled: List<T>, id: (T) -> Long) = GroupChapterFlags(
    scope = MergeScope.Group,
    pooled = pooled,
    shown = pooled,
    stitch = emptyList(),
    id = id,
    read = { false },
    bookmark = { false },
) { emptySet() }

/** Novels 1 and 2 are one group with no stored stitch. */
private fun novelReader(): GetNextNovelChapter {
    val chapters = mockk<NovelChapterRepository> {
        coEvery { getByNovelId(1L) } returns listOf(novel(10, 1))
        coEvery { getByNovelId(2L) } returns listOf(novel(20, 2))
    }
    val novels = mockk<NovelRepository> {
        coEvery { getById(any()) } answers { Novel.create().copy(id = firstArg()) }
    }
    val mergeManager = mockk<NovelMergeManager> {
        coEvery { computeRelatedIds(any()) } returns longArrayOf(1L, 2L)
    }
    val render = NovelMergedChapterProvider(mockk(), mockk())
    val provider = mockk<NovelMergedChapterProvider> {
        coEvery { stitchOf(any()) } returns emptyList()
        every { merged(any(), any()) } answers { render.merged(firstArg(), secondArg()) }
    }
    return GetNextNovelChapter(
        chapters,
        novels,
        NovelPreferences(InMemoryPreferenceStore(sequenceOf())),
        mergeManager,
        provider,
        NovelChapterSettings(novels),
    )
}
