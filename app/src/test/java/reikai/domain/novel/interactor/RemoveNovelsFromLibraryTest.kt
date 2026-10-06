package reikai.domain.novel.interactor

import android.content.Context
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import java.io.File

/**
 * The novel half of the library removal; the rule both halves share is pinned by
 * `EntryLibraryRemovalConformanceTest`. A manga and a novel can carry the same row id, and their
 * custom covers share one directory.
 */
class RemoveNovelsFromLibraryTest {

    @TempDir
    lateinit var dir: File

    private val novel = Novel.create().copy(id = 5L, favoriteAt = 100L)

    @Test
    fun `removing a novel leaves the custom cover of a manga with the same id`() = runTest {
        val cache = coverCache()
        val mangaCover = cache.getCustomCoverFile(EntryId.Manga(novel.id)).also { it.writeText("x") }

        removal(cache).await(listOf(novel.id))

        mangaCover.exists() shouldBe true
    }

    private fun coverCache(): CoverCache {
        val context = mockk<Context> {
            every { getExternalFilesDir(any()) } answers { File(dir, firstArg<String>()).also { it.mkdirs() } }
        }
        return CoverCache(context)
    }

    private fun removal(coverCache: CoverCache): RemoveNovelsFromLibrary {
        val repository = mockk<NovelRepository>(relaxed = true) {
            coEvery { getById(novel.id) } returns novel
            coEvery { updateAll(any()) } returns true
        }
        return RemoveNovelsFromLibrary(
            mergeManager = mockk(relaxed = true),
            sourceTracker = mockk(relaxed = true),
            novelRepository = repository,
            updateNovel = UpdateNovel(repository, mockk(relaxed = true)),
            coverCache = coverCache,
        )
    }
}
