package exh.eh

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.interactor.GetChapterByUrl
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.File

/** Merging a discarded gallery version's reading state into the accepted one's chapters. */
class EHentaiUpdateHelperTest {

    private fun chain(mangaId: Long, chapter: Chapter) =
        ChapterChain(Manga.create().copy(id = mangaId), listOf(chapter), emptyList())

    private val stored = Chapter.create().copy(
        id = 10L,
        mangaId = 1L,
        url = "/g/1/abc/",
        name = "v1: Gallery",
        chapterNumber = 1.0,
        sourceOrder = 0L,
    )

    private val discarded = stored.copy(id = 20L, mangaId = 2L, read = true, bookmark = true, lastPageRead = 7L)

    private fun changesFor(accepted: Chapter) =
        getChapterList(chain(1L, accepted), listOf(chain(2L, discarded)), listOf(accepted, discarded))

    private fun updateFor(accepted: Chapter) = changesFor(accepted).updates.single { it.id == accepted.id }

    @Test
    @DisplayName("an existing chapter keeps the read state merged from a discarded version")
    fun readIsSaved() {
        updateFor(stored).read shouldBe true
    }

    @Test
    @DisplayName("an existing chapter keeps the bookmark merged from a discarded version")
    fun bookmarkIsSaved() {
        updateFor(stored).bookmark shouldBe true
    }

    @Test
    @DisplayName("an existing chapter keeps the progress merged from a discarded version")
    fun progressIsSaved() {
        updateFor(stored).lastPageRead shouldBe 7L
    }

    @Test
    @DisplayName("a chapter numbered apart from its version is renamed to it")
    fun versionRenameIsWritten() {
        changesFor(stored.copy(name = "Gallery", chapterNumber = 3.0)).renames.single().name shouldBe "v1: Gallery"
    }

    @Test
    @DisplayName("a chapter already named for its version is not renamed")
    fun matchingVersionIsNotRenamed() {
        changesFor(stored).renames shouldBe emptyList()
    }

    @Test
    @DisplayName("a merged field that already matches the stored row is not rewritten")
    fun unchangedFieldIsLeftAlone() {
        updateFor(stored.copy(read = true)).read shouldBe null
    }

    @Test
    @DisplayName("the chapters a merge adds come back with the ids the database gave them")
    fun addedChaptersCarryStoredIds(@TempDir dir: File) = runTest {
        val newerVersion = Chapter.create().copy(id = 20L, mangaId = 2L, url = "/g/2/def/", name = "v2: Gallery")
        val chapterRepository = mockk<ChapterRepository>(relaxed = true) {
            coEvery { getChapterByUrl(stored.url) } returns listOf(stored)
            coEvery { getChapterByUrl(newerVersion.url) } returns listOf(newerVersion)
            coEvery { getChapterByMangaId(1L, any()) } returns listOf(stored)
            coEvery { getChapterByMangaId(2L, any()) } returns listOf(newerVersion)
            coEvery { updateFromRemote(any(), any(), any()) } answers {
                secondArg<List<Chapter>>().map { it.copy(id = 101L) }
            }
        }
        val mangaRepository = mockk<MangaRepository>(relaxed = true) {
            coEvery { getMangaById(any()) } answers {
                Manga.create().copy(id = firstArg(), source = SOURCE_ID, favoriteAt = 1L)
            }
        }
        val historyRepository = mockk<HistoryRepository>(relaxed = true) {
            coEvery { getHistoryByMangaId(any()) } returns emptyList()
        }
        val categoryRepository = mockk<CategoryRepository> {
            coEvery { getCategoriesByMangaId(any()) } returns emptyList()
        }
        val helper = EHentaiUpdateHelper(
            context = mockk<Context> { every { filesDir } returns dir },
            getChapterByUrl = GetChapterByUrl(chapterRepository),
            getChaptersByMangaId = GetChaptersByMangaId(chapterRepository),
            getManga = GetManga(mangaRepository),
            updateManga = mockk(relaxed = true),
            setMangaCategories = SetMangaCategories(mangaRepository),
            getCategories = GetCategories(categoryRepository),
            chapterRepository = chapterRepository,
            upsertHistory = UpsertHistory(historyRepository),
            removeHistory = RemoveHistory(historyRepository),
            getHistory = GetHistory(historyRepository),
            mangaMergeManager = mockk(relaxed = true),
        )

        val added = helper.findAcceptedRootAndDiscardOthers(SOURCE_ID, listOf(stored, newerVersion))!!.third

        added.map { it.id } shouldBe listOf(101L)
    }

    private companion object {
        const val SOURCE_ID = 7L
    }
}
