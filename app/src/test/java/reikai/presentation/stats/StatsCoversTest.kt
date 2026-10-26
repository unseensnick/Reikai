package reikai.presentation.stats

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.entry.EntryCustomInfo
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelCover
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.repository.MangaRepository

/** A Statistics list draws a library series' cover as the library draws it. */
class StatsCoversTest {

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a library series' cover is the one the library holds`(type: ContentType) = runTest {
        coverUrl(type, library(custom = emptyMap()), id(type, LEAD)) shouldBe "lead"
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a library series' Edit info cover wins`(type: ContentType) = runTest {
        val custom = mapOf(id(type, LEAD) to customCover(type, "custom"))
        coverUrl(type, library(custom), id(type, LEAD)) shouldBe "custom"
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a series outside the library is read by id`(type: ContentType) = runTest {
        coverUrl(type, library(custom = emptyMap()), id(type, OUTSIDE)) shouldBe "stored"
    }

    private suspend fun coverUrl(type: ContentType, library: StatsLibrary, id: EntryId): String? {
        val cover = statsCovers(listOf(id), library, mangaRepository, novelRepository)[id]
        return when (type) {
            ContentType.MANGA -> (cover as MangaCover).url
            else -> (cover as NovelCover).url
        }
    }

    // Every point read answers with a cover the in-memory lead does not have.
    private val mangaRepository = mockk<MangaRepository> {
        coEvery { getMangaById(any()) } answers { Manga.create().copy(id = firstArg(), thumbnailUrl = "stored") }
    }
    private val novelRepository = mockk<NovelRepository> {
        coEvery { getById(any()) } answers { Novel.create().copy(id = firstArg(), thumbnailUrl = "stored") }
    }

    private fun id(type: ContentType, raw: Long): EntryId =
        if (type == ContentType.MANGA) EntryId.Manga(raw) else EntryId.Novel(raw)

    private fun customCover(type: ContentType, url: String): EntryCustomInfo =
        if (type == ContentType.MANGA) {
            CustomMangaInfo(mangaId = LEAD, thumbnailUrl = url)
        } else {
            CustomNovelInfo(novelId = LEAD, thumbnailUrl = url)
        }

    private fun library(custom: Map<EntryId, EntryCustomInfo>) =
        statsLibrary(manga = listOf(manga(LEAD)), novels = listOf(novel(LEAD)), customInfo = custom)

    private fun manga(id: Long) = LibraryManga(
        manga = Manga.create().copy(id = id, thumbnailUrl = "lead"),
        categories = emptyList(),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        latestUpload = null,
        chapterFetchedAt = null,
        lastRead = null,
    )

    private fun novel(id: Long) = LibraryNovel(
        novel = Novel.create().copy(id = id, thumbnailUrl = "lead"),
        categories = emptyList(),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        downloadCount = 0,
        latestUpload = null,
        chapterFetchedAt = null,
        lastRead = null,
    )

    private companion object {
        const val LEAD = 1L
        const val OUTSIDE = 2L
    }
}
