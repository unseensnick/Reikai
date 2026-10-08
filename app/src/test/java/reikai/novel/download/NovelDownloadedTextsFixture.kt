package reikai.novel.download

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelChapterSettings
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import java.io.IOException

/** Novel 1 of source `src`, in no group, with [chapters] as its source lists them. */
object NovelDownloadedTextsFixture {

    fun chapter(id: Long, order: Long) = NovelChapter(
        id = id,
        novelId = 1L,
        url = "u$id",
        name = "Ch $order",
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = order.toDouble(),
        sourceOrder = order,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )

    /**
     * [stored] is what each chapter on disk holds, and a null value throws as an unreadable file does;
     * [hidden] are the urls the user hid.
     */
    fun texts(
        chapters: List<NovelChapter>,
        stored: Map<Long, String?>,
        hidden: Set<String> = emptySet(),
    ): NovelDownloadedTexts {
        val novel = Novel.create().copy(id = 1L, source = "src")
        val novelRepository = mockk<NovelRepository> { coEvery { getById(1L) } returns novel }
        val prefs = NovelPreferences(
            InMemoryPreferenceStore(
                sequenceOf(
                    InMemoryPreference("novel_hidden_chapters", hidden.mapTo(HashSet()) { "src|$it" }, emptySet()),
                ),
            ),
        )
        return NovelDownloadedTexts(
            GetNextNovelChapter(
                mockk<NovelChapterRepository> { coEvery { getByNovelId(1L) } returns chapters },
                novelRepository,
                prefs,
                mockk<NovelMergeManager> { coEvery { computeRelatedIds(any()) } returns longArrayOf(1L) },
                mockk(),
                NovelChapterSettings(novelRepository),
            ),
            novelRepository,
            mockk {
                every { downloadedChapterIds(any<Novel>(), any()) } answers {
                    secondArg<List<NovelChapter>>().mapNotNullTo(HashSet()) { ch -> ch.id.takeIf { it in stored } }
                }
            },
            {
                mockk {
                    every { getChapterText(any(), any()) } answers {
                        stored[secondArg<NovelChapter>().id] ?: throw IOException("unreadable")
                    }
                }
            },
        )
    }
}
