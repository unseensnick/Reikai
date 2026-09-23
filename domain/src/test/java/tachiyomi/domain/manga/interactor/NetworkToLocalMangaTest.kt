package tachiyomi.domain.manga.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

class NetworkToLocalMangaTest {

    private val inserted = mutableListOf<Manga>()
    private var stored = Manga.create()
    private val repository = mockk<MangaRepository> {
        coEvery { insertNetworkManga(any()) } answers {
            inserted += firstArg<List<Manga>>()
            listOf(stored)
        }
        coEvery { update(any()) } returns true
    }
    private val networkToLocalManga = NetworkToLocalManga(repository)

    @Test
    fun `a favourite stuck on a placeholder takes the listing cover`() = runTest {
        stored = Manga.create().copy(id = 1, favorite = true, thumbnailUrl = PLACEHOLDER)

        networkToLocalManga(Manga.create().copy(thumbnailUrl = COVER)).thumbnailUrl shouldBe COVER
    }

    @Test
    fun `a listing placeholder never reaches the store`() = runTest {
        stored = Manga.create().copy(id = 1, thumbnailUrl = COVER)

        networkToLocalManga(Manga.create().copy(thumbnailUrl = PLACEHOLDER))

        inserted.single().thumbnailUrl shouldBe null
    }

    private companion object {
        const val COVER = "https://site.example/cover.jpg"
        const val PLACEHOLDER = "https://site.example/wp-content/themes/madara/images/dflazy.jpg"
    }
}
