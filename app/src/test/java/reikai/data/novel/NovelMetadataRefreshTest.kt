package reikai.data.novel

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.host.SourceNovel
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

class NovelMetadataRefreshTest {

    private fun novel(id: Long, source: String = "src") =
        Novel.create().copy(id = id, source = source, url = "/$id", title = "N$id", thumbnailUrl = "old$id")

    private val written = mutableListOf<NovelUpdate>()
    private val progress = mutableListOf<Pair<Int, Int>>()
    private val parseFails = mutableSetOf<String>()

    private val source = mockk<NovelSource> {
        every { id } returns "src"
        coEvery { parseNovel(any()) } answers {
            val url = firstArg<String>()
            if (url in parseFails) error("offline")
            SourceNovel(path = url, name = "N" + url.drop(1), cover = "new$url")
        }
    }
    private val sourceManager = mockk<NovelSourceManager>().also {
        coEvery { it.get("src") } returns source
        coEvery { it.get("gone") } returns null
    }

    private suspend fun refresh(vararg novels: Novel) {
        val repository = mockk<NovelRepository> {
            coEvery { getFavorites() } returns novels.toList()
            coEvery { update(any<NovelUpdate>()) } answers {
                written += firstArg<NovelUpdate>()
                true
            }
        }
        NovelMetadataRefresh(
            novelRepository = repository,
            sourceManager = sourceManager,
            installer = mockk { coEvery { ensureLoaded() } just runs },
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            coverCache = mockk(relaxed = true),
            notifier = mockk(relaxed = true),
            novelDownloadManager = { mockk(relaxed = true) },
        ).run { _, done, total -> progress += done to total }
    }

    @Test
    fun `a library novel takes its source's cover`() = runTest {
        refresh(novel(1))

        written.single().thumbnailUrl shouldBe "new/1"
    }

    /** The update walks on from the stored count, so raising it here would skip the pages between. */
    @Test
    fun `a refresh leaves the stored page count for the update to walk`() = runTest {
        coEvery { source.parseNovel("/1") } returns
            SourceNovel(path = "/1", name = "N1", cover = "new/1", totalPages = 4)

        refresh(novel(1).copy(totalPages = 2L))

        written.single().totalPages shouldBe 2L
    }

    @Test
    fun `a novel that fails leaves the next one to refresh`() = runTest {
        parseFails += "/1"

        refresh(novel(1), novel(2))

        written.single().id shouldBe 2L
    }

    @Test
    fun `progress reaches the end through its caller`() = runTest {
        refresh(novel(1))

        progress shouldBe listOf(0 to 1, 1 to 1)
    }

    @Test
    fun `a novel whose source is gone is skipped`() = runTest {
        refresh(novel(1, source = "gone"), novel(2))

        written.single().id shouldBe 2L
    }
}
