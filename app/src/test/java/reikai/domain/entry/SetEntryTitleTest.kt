package reikai.domain.entry

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.SetCustomNovelInfo
import reikai.domain.novel.model.CustomNovelInfo
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo

class SetEntryTitleTest {

    private val savedManga = slot<CustomMangaInfo>()
    private val savedNovel = slot<CustomNovelInfo>()

    private val setTitle = SetEntryTitle(
        getCustomMangaInfo = mockk {
            every { subscribe(1L) } returns
                flowOf(CustomMangaInfo(1L, title = "Old", author = "A"))
        },
        setCustomMangaInfo = mockk { coEvery { set(capture(savedManga)) } returns Unit },
        getCustomNovelInfo = mockk {
            every { subscribe(1L) } returns
                flowOf(CustomNovelInfo(1L, title = "Old", author = "A"))
        },
        setCustomNovelInfo = mockk { coEvery { set(capture(savedNovel)) } returns Unit },
    )

    @Test
    fun `a manga's new title keeps its other overrides`() = runTest {
        setTitle.await(EntryId.Manga(1L), "New")

        savedManga.captured shouldBe CustomMangaInfo(1L, title = "New", author = "A")
    }

    @Test
    fun `a novel's new title keeps its other overrides`() = runTest {
        setTitle.await(EntryId.Novel(1L), "New")

        savedNovel.captured shouldBe CustomNovelInfo(1L, title = "New", author = "A")
    }

    @Test
    fun `it answers the title it had before, for an undo`() = runTest {
        setTitle.await(EntryId.Novel(1L), "New") shouldBe "Old"
    }
}
