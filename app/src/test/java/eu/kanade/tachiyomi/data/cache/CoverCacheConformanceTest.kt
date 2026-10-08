package eu.kanade.tachiyomi.data.cache

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import tachiyomi.domain.manga.model.Manga
import java.io.File

/**
 * The cover cache's set and delete as each content type calls them: manga through upstream's Manga
 * overloads, novels through the entry-keyed ones. Both must write and clear the same files.
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

    class CoverType(
        private val name: String,
        val id: EntryId,
        val set: (CoverCache, java.io.InputStream) -> Unit,
        val delete: (CoverCache) -> Int,
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
            ),
            CoverType(
                "novel",
                EntryId.Novel(5L),
                set = { cache, stream -> cache.setCustomCoverToCache(EntryId.Novel(5L), stream) },
                delete = { it.deleteFromCache(EntryId.Novel(5L), THUMBNAIL, deleteCustomCover = true) },
            ),
        )
    }
}
