package mihon.core.migration.migrations

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import mihon.domain.source.interactor.UpdateMangaFromRemote
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.novel.local.LocalNovelSource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.source.local.LocalSource

/** The upgrade reads every local series in the library again, of both types, and nothing else. */
class LocalSeriesLanguageMigrationTest {

    private val rereadManga = mutableListOf<Long>()
    private val rereadNovels = mutableListOf<String>()

    private suspend fun TestScope.upgrade() {
        val mangas = mockk<MangaRepository> {
            coEvery { getFavorites() } returns listOf(
                Manga.create().copy(id = 1L, source = LocalSource.ID),
                Manga.create().copy(id = 2L, source = 5L),
            )
        }
        val novels = mockk<NovelRepository> {
            coEvery { getFavorites() } returns listOf(
                Novel.create().copy(id = 3L, source = LocalNovelSource.ID, url = "Local"),
                Novel.create().copy(id = 4L, source = "plugin", url = "Online"),
            )
        }
        val update = mockk<UpdateMangaFromRemote> {
            coEvery { this@mockk.invoke(any<Manga>(), any(), any(), any(), any()) } answers {
                rereadManga += firstArg<Manga>().id
                Result.failure(Exception("not read in a test"))
            }
        }
        val localNovels = mockk<LocalNovelSource> {
            coEvery { parseNovel(any()) } answers {
                rereadNovels += firstArg<String>()
                mockk()
            }
        }
        LocalSeriesLanguageMigration(mangas, novels, update, localNovels, this)
            .invoke(MigrationContext(dryrun = false, previousVersion = 205))
        advanceUntilIdle()
    }

    @Test
    fun `only local manga are read again`() = runTest {
        upgrade()

        rereadManga shouldBe listOf(1L)
    }

    @Test
    fun `only local novels are read again`() = runTest {
        upgrade()

        rereadNovels shouldBe listOf("Local")
    }
}
