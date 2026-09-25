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

class RemoveNovelsFromLibraryTest {

    @TempDir
    lateinit var dir: File

    private val novel = Novel.create().copy(id = 5L, thumbnailUrl = "https://example.org/cover.jpg", favorite = true)

    private fun coverCache(): CoverCache {
        val context = mockk<Context> {
            every { getExternalFilesDir(any()) } answers { File(dir, firstArg<String>()).also { it.mkdirs() } }
        }
        return CoverCache(context)
    }

    private fun step(coverCache: CoverCache, favoriteWritten: Boolean = true) = RemoveNovelsFromLibrary(
        mergeManager = mockk(relaxed = true),
        updateNovel = mockk(relaxed = true) {
            coEvery { awaitUpdateFavorite(any(), any()) } returns favoriteWritten
        },
        novelRepository = mockk<NovelRepository> { coEvery { getById(novel.id) } returns novel },
        coverCache = coverCache,
    )

    @Test
    fun `a removed novel loses its cached library cover`() = runTest {
        val cache = coverCache()
        val cover = cache.getCoverFile(novel.thumbnailUrl)!!.also { it.writeText("x") }

        step(cache).await(listOf(novel.id))

        cover.exists() shouldBe false
    }

    @Test
    fun `a removed novel loses its custom cover`() = runTest {
        val cache = coverCache()
        val custom = cache.getCustomCoverFile(EntryId.Novel(novel.id)).also { it.writeText("x") }

        step(cache).await(listOf(novel.id))

        custom.exists() shouldBe false
    }

    @Test
    fun `a novel whose favourite write failed keeps its cover`() = runTest {
        val cache = coverCache()
        val cover = cache.getCoverFile(novel.thumbnailUrl)!!.also { it.writeText("x") }

        step(cache, favoriteWritten = false).await(listOf(novel.id))

        cover.exists() shouldBe true
    }
}
