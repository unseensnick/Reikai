package reikai.data.coil

import android.content.Context
import coil3.request.Options
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.MangaCoverKeyer
import eu.kanade.tachiyomi.data.coil.MangaKeyer
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
import reikai.util.idleAppScope
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import java.io.File
import kotlin.time.Instant

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
        coverCache = CoverCache(idleAppScope(), context)
        options = Options(context)
    }

    @Test
    fun `a browsed novel keeps its cover key once it is stored`() {
        novelKey(novelResultCover(null, URL, SOURCE)) shouldBe novelKey(novel(id = 7L, lastModified = null))
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

        mangaKey(manga(id = 7L)) shouldBe "7;1970-01-01T00:00:00.042Z"
    }

    @Test
    fun `a manga's source cover keeps Mihon's key`() {
        mangaKey(manga(id = 7L)) shouldBe "$URL;1970-01-01T00:00:00.042Z"
    }

    @Test
    fun `a manga's custom cover left outside the library is not keyed`() {
        customCover(EntryId.Manga(7L))

        mangaKey(manga(id = 7L, favorite = false)) shouldBe "$URL;1970-01-01T00:00:00.042Z"
    }

    @Test
    fun `a novel's custom cover left outside the library is not keyed`() {
        customCover(EntryId.Novel(7L))

        novelKey(novel(id = 7L, favorite = false)) shouldBe
            novelKey(novelResultCover(null, URL, SOURCE).copy(lastModified = LAST_MODIFIED))
    }

    @Test
    fun `MangaKeyer keys a manga outside the library by its address`() {
        customCover(EntryId.Manga(7L))
        val manga = Manga.create().copy(id = 7L, thumbnailUrl = URL, coverLastModified = LAST_MODIFIED)

        MangaKeyer().key(manga, options) shouldBe "$URL;1970-01-01T00:00:00.042Z"
    }

    @Test
    fun `an entry outside the library owns no custom cover`() {
        libraryCustomCoverOwner(EntryId.Manga(7L), isFavorite = false) { true } shouldBe null
    }

    @Test
    fun `a library entry owns the custom cover it has`() {
        libraryCustomCoverOwner(EntryId.Novel(7L), isFavorite = true) { true } shouldBe EntryId.Novel(7L)
    }

    @Test
    fun `a library entry without a custom cover owns none`() {
        libraryCustomCoverOwner(EntryId.Novel(7L), isFavorite = true) { false } shouldBe null
    }

    private fun customCover(owner: EntryId) {
        coverCache.getCustomCoverFile(owner).writeText("cover")
    }

    private fun novelKey(cover: NovelCover) = NovelCoverKeyer(coverCache).key(cover, options)

    private fun mangaKey(cover: MangaCover) = MangaCoverKeyer(coverCache).key(cover, options)

    private fun novel(id: Long, url: String = URL, lastModified: Instant? = LAST_MODIFIED, favorite: Boolean = true) =
        NovelCover(url = url, sourceId = SOURCE, isNovelFavorite = favorite, lastModified = lastModified, novelId = id)

    private fun manga(id: Long, favorite: Boolean = true) =
        MangaCover(mangaId = id, sourceId = 1L, isMangaFavorite = favorite, url = URL, lastModified = LAST_MODIFIED)

    private companion object {
        const val URL = "https://example.org/cover.jpg"
        const val SOURCE = "plugin"
        val LAST_MODIFIED = Instant.fromEpochMilliseconds(42L)
    }
}
