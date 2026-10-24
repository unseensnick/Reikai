package exh.eh

import android.content.Context
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.manga.MangaMergeManager
import reikai.domain.readinglog.NoReadingLog
import reikai.domain.readinglog.ReadingLogRepository
import reikai.presentation.browse.FakeMangaLibrary
import reikai.presentation.browse.fakeGrouping
import tachiyomi.domain.chapter.interactor.GetChapterByUrl
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
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

    private val discarded = stored.copy(id = 20L, mangaId = 2L, read = true, bookmark = true, lastPageRead = 7)

    private val newerVersion = Chapter.create().copy(id = 20L, mangaId = 2L, url = "/g/2/def/", name = "v2: Gallery")

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
        val reconcile = Reconcile(dir, acceptedInLibrary = true)

        val added = reconcile.run(newerVersion)!!.third

        added.map { it.id } shouldBe listOf(101L)
    }

    @Test
    @DisplayName("an accepted version joining the library is heard by its source's tracker")
    fun acceptedJoinIsHeard(@TempDir dir: File) = runTest {
        val reconcile = Reconcile(dir, acceptedInLibrary = false)

        reconcile.run(newerVersion)

        reconcile.library.toldSourceTracker shouldContain (EntryId.Manga(1L) to true)
    }

    @Test
    @DisplayName("a discarded version leaving the library is heard by its source's tracker")
    fun discardedLeaveIsHeard(@TempDir dir: File) = runTest {
        val reconcile = Reconcile(dir, acceptedInLibrary = false)

        reconcile.run(newerVersion)

        reconcile.library.toldSourceTracker shouldContain (EntryId.Manga(2L) to false)
    }

    @Test
    @DisplayName("a refused add of the accepted version leaves the discarded one in the library")
    fun refusedAddKeepsTheLibrary(@TempDir dir: File) = runTest {
        val reconcile = Reconcile(dir, acceptedInLibrary = false)
        reconcile.library.refusedFavoriteWrites += 1L

        reconcile.run(newerVersion)

        reconcile.library.rows.getValue(2L).favorite shouldBe true
    }

    @Test
    @DisplayName("an accepted version the reconcile adds comes back as in the library")
    fun acceptedComesBackFavorited(@TempDir dir: File) = runTest {
        val reconcile = Reconcile(dir, acceptedInLibrary = false)

        val accepted = reconcile.run(newerVersion)!!.first

        accepted.manga.favorite shouldBe true
    }

    @Test
    @DisplayName("a discarded version's reading time moves to the chapter the merge inserts")
    fun readingTimeMovesToInsertedChapter(@TempDir dir: File) = runTest {
        val reconcile = Reconcile(dir, acceptedInLibrary = true)

        reconcile.run(newerVersion)

        reconcile.moves shouldBe mapOf(20L to 101L)
    }

    @Test
    @DisplayName("a discarded chapter sharing the accepted chapter's url moves its reading time there")
    fun readingTimeMovesToSharedUrl(@TempDir dir: File) = runTest {
        val reconcile = Reconcile(dir, acceptedInLibrary = true)

        reconcile.run(discarded)

        reconcile.moves shouldBe mapOf(20L to 10L)
    }

    /**
     * Gallery 1 holding [stored] (the accepted version, the lowest id) and gallery 2 in the library holding
     * the chapter a run passes, over [FakeMangaLibrary]. An insert changes what the accepted gallery's
     * chapter read answers, as the database would.
     */
    private inner class Reconcile(dir: File, acceptedInLibrary: Boolean, groupOfAccepted: List<Long> = listOf(1L)) {
        val library = FakeMangaLibrary(
            mergeManager = fakeGrouping<MangaMergeManager>().apply {
                coEvery { computeRelatedIds(1L) } returns groupOfAccepted.toLongArray()
            },
        ).apply {
            put(1L, favorite = acceptedInLibrary, categories = listOf(1L))
            put(2L, favorite = true, categories = listOf(2L))
            groupOfAccepted.drop(1).forEach { put(it, favorite = true, categories = listOf(3L)) }
        }
        val moves = mutableMapOf<Long, Long>()
        private var acceptedRows = listOf(stored)

        private val readingLog = object : ReadingLogRepository by NoReadingLog {
            override suspend fun move(contentType: ContentType, targetBySourceChapter: Map<Long, Long>) {
                moves.putAll(targetBySourceChapter)
            }
        }

        private fun chapterRepository(other: Chapter) = mockk<ChapterRepository>(relaxed = true) {
            coEvery { getChapterByUrl(any()) } answers {
                listOf(stored, other).filter { it.url == firstArg<String>() }
            }
            coEvery { getChapterByMangaId(1L, any()) } answers { acceptedRows }
            coEvery { getChapterByMangaId(2L, any()) } returns listOf(other)
            coEvery { updateFromRemote(any(), any(), any()) } answers {
                secondArg<List<Chapter>>().map { it.copy(id = 101L, mangaId = 1L) }
                    .also { acceptedRows = acceptedRows + it }
            }
        }

        private val historyRepository = mockk<HistoryRepository>(relaxed = true) {
            coEvery { getHistoryByMangaId(any()) } returns emptyList()
        }

        private val context = mockk<Context> { every { filesDir } returns dir }

        suspend fun run(other: Chapter): Triple<ChapterChain, List<ChapterChain>, List<Chapter>>? {
            val chapterRepository = chapterRepository(other)
            val helper = EHentaiUpdateHelper(
                context = context,
                getChapterByUrl = GetChapterByUrl(chapterRepository),
                getChaptersByMangaId = GetChaptersByMangaId(chapterRepository),
                getManga = library.getManga,
                libraryAdder = library.adder,
                removeMangaFromLibrary = library.removal,
                setMangaCategories = library.setMangaCategories,
                getCategories = library.getCategories,
                chapterRepository = chapterRepository,
                upsertHistory = UpsertHistory(historyRepository),
                removeHistory = RemoveHistory(historyRepository),
                getHistory = GetHistory(historyRepository),
                readingLog = readingLog,
            )
            return helper.findAcceptedRootAndDiscardOthers(FakeMangaLibrary.SOURCE_ID, listOf(stored, other))
        }
    }
}
