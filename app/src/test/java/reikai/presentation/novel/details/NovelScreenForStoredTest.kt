package reikai.presentation.novel.details

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel

class NovelScreenForStoredTest {

    private val repository = mockk<NovelRepository> {
        coEvery { getById(7L) } returns Novel.create().copy(id = 7L, source = "plugin", url = "/novel/7")
        coEvery { getById(8L) } returns null
    }

    @Test
    fun `a stored novel opens the page keyed by its source and url`() = runTest {
        val screen = NovelScreen.forStored(7L, repository)

        (screen?.sourceId to screen?.novelUrl) shouldBe ("plugin" to "/novel/7")
    }

    @Test
    fun `an id with no stored novel opens nothing`() = runTest {
        NovelScreen.forStored(8L, repository) shouldBe null
    }
}
