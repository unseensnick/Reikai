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
        ).run()
    }

    @Test
    fun `a library novel takes its source's cover`() = runTest {
        refresh(novel(1))

        written.single().thumbnailUrl shouldBe "new/1"
    }

    @Test
    fun `a novel that fails leaves the next one to refresh`() = runTest {
        parseFails += "/1"

        refresh(novel(1), novel(2))

        written.single().id shouldBe 2L
    }

    @Test
    fun `a novel whose source is gone is skipped`() = runTest {
        refresh(novel(1, source = "gone"), novel(2))

        written.single().id shouldBe 2L
    }
}
