package reikai.data.novel

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.data.novel.update.NovelUpdateNotifier
import reikai.domain.download.SourceContacts
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.host.SourceNovel
import reikai.novel.source.LockedSeriesSource
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

class NovelMetadataRefreshTest {

    private fun novel(id: Long, source: String = "src") =
        Novel.create().copy(id = id, source = source, url = "/$id", title = "N$id", thumbnailUrl = "old$id")

    private val written = mutableListOf<NovelUpdate>()
    private val stored = mutableMapOf<Long, Novel>()
    private val progress = mutableListOf<Pair<Int, Int>>()
    private val parseFails = mutableSetOf<String>()
    private val notifier = mockk<NovelUpdateNotifier>(relaxed = true)

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
        coEvery { it.get("local") } returns HeldSource(source)
        coEvery { it.get("gone") } returns null
    }

    private suspend fun refresh(vararg novels: Novel) {
        val repository = mockk<NovelRepository> {
            coEvery { getFavorites() } returns novels.toList()
            coEvery { getById(any()) } answers { stored[firstArg()] }
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
            notifier = notifier,
            novelDownloadManager = { mockk(relaxed = true) },
            sourceContacts = SourceContacts { 0L },
        ).run { _, done, total -> progress += done to total }
    }

    /** A relink or organize after the run read the library moves the series; the refresh reads where it is now. */
    @Test
    fun `a local novel moved since the run began is refreshed at its new address`() = runTest {
        // Titled as the source names it, so no retitle hands the write to the download manager
        val snapshot = novel(1, source = "local").copy(title = "Nmoved")
        stored[1L] = snapshot.copy(url = "/moved")

        refresh(snapshot)

        written.single().thumbnailUrl shouldBe "new/moved"
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

    /** The notice throttles most ticks, so a tick's titles are looked up only when the notice asks. */
    @Test
    fun `a progress tick reads no titles unless the notice asks for them`() = runTest {
        refresh(novel(1))

        coVerify(exactly = 0) { notifier.visibleTitles(any()) }
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

/** A source that holds each series while a block runs, as the local source does. */
private class HeldSource(inner: NovelSource) :
    NovelSource by inner,
    LockedSeriesSource {
    override suspend fun <T> locked(novelPath: String, block: suspend () -> T): T = block()
}
