package reikai.domain.entry

import android.content.Context
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.NovelUpdate
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.File

/**
 * Clearing a custom cover (the viewer's Delete, edit info's Reset all) removes the entry's cover file and
 * stamps a new cover key on its own row, so the cover already in memory is not served again. A manga
 * and a novel can share a row id, so each case also checks the other type is left alone.
 */
class ClearCustomCoverConformanceTest {

    @TempDir
    lateinit var dir: File

    @ParameterizedTest(name = "{0}")
    @MethodSource("ids")
    fun `clearing deletes the entry's custom cover file`(id: EntryId) = runTest {
        val covers = Covers(dir)
        val file = covers.cache.getCustomCoverFile(id).also { it.writeText("x") }

        covers.kernel.await(id)

        file.exists() shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ids")
    fun `clearing keeps the other type's cover under the same row id`(id: EntryId) = runTest {
        val covers = Covers(dir)
        val other = covers.cache.getCustomCoverFile(id.other()).also { it.writeText("x") }

        covers.kernel.await(id)

        other.exists() shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ids")
    fun `clearing stamps a new cover key on the entry's own row`(id: EntryId) = runTest {
        val covers = Covers(dir)

        covers.kernel.await(id)

        covers.stamped shouldBe listOf(id)
    }

    companion object {
        @JvmStatic
        fun ids() = listOf(EntryId.Manga(5L), EntryId.Novel(5L))
    }
}

private fun EntryId.other(): EntryId = when (this) {
    is EntryId.Manga -> EntryId.Novel(rawId)
    is EntryId.Novel -> EntryId.Manga(rawId)
}

/** The real kernel over a cover cache on disk, with both tables recording the cover-key stamps they take. */
private class Covers(dir: File) {
    val stamped = mutableListOf<EntryId>()

    val cache = CoverCache(
        mockk<Context> {
            every { getExternalFilesDir(any()) } answers { File(dir, firstArg<String>()).apply { mkdirs() } }
        },
    )

    private val mangaRepository = mockk<MangaRepository> {
        coEvery { update(any()) } answers {
            val update = firstArg<MangaUpdate>()
            if (update.isSet(MangaUpdate::coverLastModified)) stamped += EntryId.Manga(update.id)
            true
        }
    }

    private val novelRepository = mockk<NovelRepository> {
        coEvery { update(any<NovelUpdate>()) } answers {
            val update = firstArg<NovelUpdate>()
            if (update.isSet(NovelUpdate::coverLastModified)) stamped += EntryId.Novel(update.id)
            true
        }
    }

    val kernel = ClearCustomCover(
        coverCache = cache,
        updateManga = UpdateManga(mangaRepository, fetchInterval = mockk(), sourceTracker = mockk(relaxed = true)),
        updateNovel = UpdateNovel(novelRepository, sourceTracker = mockk(relaxed = true)),
    )
}
