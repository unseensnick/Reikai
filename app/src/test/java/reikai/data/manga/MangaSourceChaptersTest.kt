package reikai.data.manga

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.chapter.NoChapterNumberOverrides
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

/** A manga source's chapter list as the sync stores it, which the migration count peek counts too. */
class MangaSourceChaptersTest {

    private val manga = Manga.create().copy(id = 7L, title = "Title")

    private fun chapter(url: String, name: String) = SChapter.create().apply {
        this.url = url
        this.name = name
    }

    // The source's deprecated hook, which an older extension still uses to fix a number or a url.
    private fun preparing(prepare: (SChapter) -> Unit): Source = mockk<HttpSource>(relaxed = true) {
        @Suppress("DEPRECATION")
        every { prepareNewChapter(any(), any()) } answers { prepare(firstArg()) }
    }

    private suspend fun stored(raw: List<SChapter>, source: Source): List<Chapter> {
        var added = emptyList<Chapter>()
        val repository = mockk<ChapterRepository> {
            coEvery { getChapterByMangaId(any(), any()) } returns emptyList()
            coEvery { updateFromRemote(any(), any(), any()) } answers { secondArg<List<Chapter>>().also { added = it } }
        }
        SyncChaptersWithSource(
            downloadManager = mockk(relaxed = true),
            downloadProvider = mockk(relaxed = true),
            chapterRepository = repository,
            shouldUpdateDbChapter = mockk(relaxed = true),
            updateManga = mockk(relaxed = true),
            getExcludedScanlators = mockk { coEvery { await(any()) } returns emptySet() },
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            chapterNumberOverrides = NoChapterNumberOverrides,
        ).await(raw, manga, source)
        return added
    }

    @Test
    fun `a sync stores the list as toSourceChapters reads it`() = runTest {
        val source = preparing { if (it.url == "/b") it.chapter_number = 9f }
        val raw = listOf(chapter("/a", "Title Chapter 1"), chapter("/b", "Extra"), chapter("/a", "Again"))
        fun List<Chapter>.shape() = map { listOf(it.url, it.name, it.chapterNumber, it.sourceOrder) }

        stored(raw, source).shape() shouldBe raw.toSourceChapters(manga, source).shape()
    }

    @Test
    fun `a url the source lists twice is stored once, without a gap in the order`() = runTest {
        stored(listOf(chapter("/a", "One"), chapter("/a", "One"), chapter("/b", "Two")), mockk(relaxed = true))
            .map { it.url to it.sourceOrder } shouldBe listOf("/a" to 0L, "/b" to 1L)
    }

    @Test
    fun `a url the prepare hook makes collide is stored once, keeping the order stamped before it`() = runTest {
        val source = preparing { if (it.url == "/b") it.url = "/a" }
        stored(listOf(chapter("/a", "One"), chapter("/b", "Two"), chapter("/c", "Three")), source)
            .map { it.url to it.sourceOrder } shouldBe listOf("/a" to 0L, "/c" to 2L)
    }

    @Test
    fun `the series title in front of a chapter name is dropped`() = runTest {
        stored(listOf(chapter("/a", "Title - Chapter 5")), mockk(relaxed = true)).single().name shouldBe "Chapter 5"
    }

    @Test
    fun `a number the prepare hook sets is the one stored`() = runTest {
        val source = preparing { it.chapter_number = 42f }
        stored(listOf(chapter("/a", "Extra")), source).single().chapterNumber shouldBe 42.0
    }

    @Test
    fun `a number the source left unset is read from the name`() = runTest {
        stored(listOf(chapter("/a", "Chapter 7")), mockk(relaxed = true)).single().chapterNumber shouldBe 7.0
    }
}
