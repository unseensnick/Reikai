package eu.kanade.tachiyomi.data.cache

import android.content.Context
import eu.kanade.domain.manga.model.hasCustomCover
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.hasCustomCover
import tachiyomi.domain.manga.model.Manga
import java.io.File

/**
 * The cover cache's set, delete and custom-cover check as each content type calls them: manga through
 * upstream's Manga overloads, novels through the entry-keyed ones. Both must write, clear and find the
 * same files.
 */
class CoverCacheConformanceTest {

    @TempDir
    lateinit var dir: File

    private fun cache() = CoverCache(
        mockk<Context> {
            every { getExternalFilesDir(any()) } answers { File(dir, firstArg<String>()).apply { mkdirs() } }
        },
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `setting a custom cover writes the entry's custom cover file`(type: CoverType) {
        val cache = cache()

        type.set(cache, "x".byteInputStream())

        cache.getCustomCoverFile(type.id).readText() shouldBe "x"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `deleting with the custom cover removes both the cached and the custom cover`(type: CoverType) {
        val cache = cache()
        cache.getCoverFile(THUMBNAIL)!!.writeText("x")
        cache.getCustomCoverFile(type.id).writeText("x")

        type.delete(cache) shouldBe 2
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `a custom cover set for the entry is seen as set`(type: CoverType) {
        val cache = cache()

        type.set(cache, "x".byteInputStream())

        type.has(cache) shouldBe true
    }

    /** Both entries have id 5, so this is the namespacing that keeps a manga and a novel apart. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `the other type's custom cover under the same id is not the entry's`(type: CoverType) {
        val cache = cache()

        types().single { it.toString() != type.toString() }.set(cache, "x".byteInputStream())

        type.has(cache) shouldBe false
    }

    class CoverType(
        private val name: String,
        val id: EntryId,
        val set: (CoverCache, java.io.InputStream) -> Unit,
        val delete: (CoverCache) -> Int,
        val has: (CoverCache) -> Boolean,
    ) {
        override fun toString() = name
    }

    companion object {
        private const val THUMBNAIL = "https://example.org/cover.jpg"
        private val manga = Manga.create().copy(id = 5L, thumbnailUrl = THUMBNAIL)

        @JvmStatic
        fun types() = listOf(
            CoverType(
                "manga",
                EntryId.Manga(5L),
                set = { cache, stream -> cache.setCustomCoverToCache(manga, stream) },
                delete = { it.deleteFromCache(manga, deleteCustomCover = true) },
                has = { manga.hasCustomCover(it) },
            ),
            CoverType(
                "novel",
                EntryId.Novel(5L),
                set = { cache, stream -> cache.setCustomCoverToCache(EntryId.Novel(5L), stream) },
                delete = { it.deleteFromCache(EntryId.Novel(5L), THUMBNAIL, deleteCustomCover = true) },
                has = { Novel.create().copy(id = 5L).hasCustomCover(it) },
            ),
        )
    }
}
