package reikai.presentation.library.novels

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.library.LibraryGroup

class NovelDynamicGroupingTest {

    @Test
    fun `grouping by language keys a plugin's own language name by its code`() = runTest {
        val declared = listOf("Polski", "Português", "Español", "Multi")
        val novels = declared.mapIndexed { index, lang -> libraryNovel(index.toLong(), source = lang) }
        val sources = declared.associateWith { declaredLang ->
            mockk<NovelSource> { every { lang } returns declaredLang }
        }
        val sourceManager = mockk<NovelSourceManager>()
        coEvery { sourceManager.get(any()) } answers { sources[firstArg()] }

        val feed = novelDynamicGroupingFeed(
            items = novels.map { it.toLibraryItem(false, false, false, "", false, null, "") },
            novelById = novels.associateBy { it.novel.id },
            tracksByRep = emptyMap(),
            loggedInTrackerIds = emptySet(),
            groupType = LibraryGroup.BY_LANGUAGE,
            sourceManager = sourceManager,
            trackerManager = mockk(),
            context = mockk(),
        )

        feed.languageCodes.values.toList() shouldBe listOf("pl", "pt", "es", "all")
    }

    private fun libraryNovel(id: Long, source: String) = LibraryNovel(
        novel = Novel.create().copy(id = id, title = "Title $id", url = "/n/$id", source = source),
        categories = emptyList(),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        downloadCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
    )
}
