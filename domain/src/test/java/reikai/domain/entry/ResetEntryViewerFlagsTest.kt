package reikai.domain.entry

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelRepository
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import tachiyomi.domain.manga.repository.MangaRepository

class ResetEntryViewerFlagsTest {

    private val mangaRepository = mockk<MangaRepository>()
    private val novelRepository = mockk<NovelRepository>()
    private val reset = ResetEntryViewerFlags(ResetViewerFlags(mangaRepository), novelRepository)

    private fun outcome(manga: Boolean, novels: Boolean) {
        coEvery { mangaRepository.resetViewerFlags() } returns manga
        coEvery { novelRepository.resetViewerFlags() } returns novels
    }

    @Test
    fun `succeeds when both types reset`() = runTest {
        outcome(manga = true, novels = true)

        reset.await() shouldBe true
    }

    @Test
    fun `fails when novels could not reset`() = runTest {
        outcome(manga = true, novels = false)

        reset.await() shouldBe false
    }

    @Test
    fun `fails when manga could not reset`() = runTest {
        outcome(manga = false, novels = true)

        reset.await() shouldBe false
    }

    @Test
    fun `novels still reset when manga could not`() = runTest {
        outcome(manga = false, novels = true)

        reset.await()

        coVerify { novelRepository.resetViewerFlags() }
    }
}
