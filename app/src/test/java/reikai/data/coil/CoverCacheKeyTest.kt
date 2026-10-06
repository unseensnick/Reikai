package reikai.data.coil

import android.content.Context
import coil3.request.Options
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.MangaCoverKeyer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.domain.entry.EntryId
import reikai.domain.novel.model.NovelCover
import reikai.domain.novel.model.novelResultCover
import tachiyomi.domain.manga.model.MangaCover
import java.io.File

/**
 * Manga and novel covers are keyed by one rule, `coverCacheKey`, and Coil caches a cover under that key
 * in memory and on disk alike, so two keys for one picture fetch and store it twice.
 */
class CoverCacheKeyTest {

    @TempDir
    lateinit var dir: File

    private lateinit var coverCache: CoverCache
    private lateinit var options: Options

    @BeforeEach
    fun setUp() {
        val context = mockk<Context> { every { getExternalFilesDir(any()) } returns dir }
        coverCache = CoverCache(context)
        options = Options(context)
    }

    @Test
    fun `a browsed novel keeps its cover key once it is stored`() {
        novelKey(novelResultCover(null, URL, SOURCE)) shouldBe novelKey(novel(id = 7L, lastModified = 0L))
    }

    @Test
    fun `a novel's custom cover is keyed by its owner, not its address`() {
        customCover(EntryId.Novel(7L))

        novelKey(novel(id = 7L, url = "https://other")) shouldBe novelKey(novel(id = 7L))
    }

    @Test
    fun `a manga and a novel with the same id never share a custom cover key`() {
        customCover(EntryId.Manga(7L))
        customCover(EntryId.Novel(7L))

        mangaKey(manga(id = 7L)) shouldNotBe novelKey(novel(id = 7L))
    }

    @Test
    fun `a manga's custom cover keeps Mihon's key`() {
        customCover(EntryId.Manga(7L))

        mangaKey(manga(id = 7L)) shouldBe "7;42"
    }

    @Test
    fun `a manga's source cover keeps Mihon's key`() {
        mangaKey(manga(id = 7L)) shouldBe "$URL;42"
    }

    private fun customCover(owner: EntryId) {
        coverCache.getCustomCoverFile(owner).writeText("cover")
    }

    private fun novelKey(cover: NovelCover) = NovelCoverKeyer(coverCache).key(cover, options)

    private fun mangaKey(cover: MangaCover) = MangaCoverKeyer(coverCache).key(cover, options)

    private fun novel(id: Long, url: String = URL, lastModified: Long = 42L) =
        NovelCover(url = url, sourceId = SOURCE, isNovelFavorite = true, lastModified = lastModified, novelId = id)

    private fun manga(id: Long) =
        MangaCover(mangaId = id, sourceId = 1L, isMangaFavorite = true, url = URL, lastModified = 42L)

    private companion object {
        const val URL = "https://example.org/cover.jpg"
        const val SOURCE = "plugin"
    }
}
