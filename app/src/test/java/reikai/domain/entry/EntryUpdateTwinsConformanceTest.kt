package reikai.domain.entry

import eu.kanade.domain.manga.interactor.UpdateManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import reikai.data.novel.expectedNextUpdate
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.track.source.SourceTrackerDispatcher
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.time.Instant

/** The novel halves of Mihon's manga rules, each run beside the manga original. */
class EntryUpdateTwinsConformanceTest {

    @ParameterizedTest(name = "status {0}")
    @ValueSource(longs = [1L, 2L])
    fun `an entry is next due when manga's rule says, and never once completed`(status: Long) {
        val novel = Novel.create().copy(status = status, nextUpdate = NEXT_UPDATE)
        val manga = Manga.create().copy(status = status, nextUpdate = NEXT_UPDATE)

        novel.expectedNextUpdate() shouldBe manga.expectedNextUpdate
    }

    @Test
    fun `an entry never predicted is never due, on either type`() {
        val novel = Novel.create().copy(nextUpdate = null)
        val manga = Manga.create().copy(nextUpdate = null)

        (novel.expectedNextUpdate() to manga.expectedNextUpdate) shouldBe (null to null)
    }

    @ParameterizedTest(name = "favorite {0}")
    @ValueSource(booleans = [true, false])
    fun `adding and removing stamp the join date and tell the source's tracker alike`(favorite: Boolean) = runTest {
        val told = mutableListOf<Pair<EntryId, Boolean>>()
        val tracker = mockk<SourceTrackerDispatcher> {
            every { favoriteChanged(any(), any()) } answers { told += firstArg<EntryId>() to secondArg() }
        }
        val mangaWrite = slot<MangaUpdate>()
        val novelWrite = slot<NovelUpdate>()
        val mangaRepository = mockk<MangaRepository> { coEvery { update(capture(mangaWrite)) } returns true }
        val novelRepository = mockk<NovelRepository> { coEvery { update(capture(novelWrite)) } returns true }

        UpdateManga(mangaRepository, mockk(), tracker).awaitUpdateFavorite(ID, favorite)
        UpdateNovel(novelRepository, tracker).awaitUpdateFavorite(ID, favorite)

        listOf(
            mangaWrite.captured.isSet(MangaUpdate::favoriteAt) to (mangaWrite.captured.favoriteAt != null),
            novelWrite.captured.isSet(NovelUpdate::favoriteAt) to (novelWrite.captured.favoriteAt != null),
            told.single { it.first is EntryId.Novel }.second to told.single { it.first is EntryId.Manga }.second,
        ) shouldBe listOf(true to favorite, true to favorite, favorite to favorite)
    }

    /** No cover load need follow an add, so the add itself moves the colour out from under the browse cap. */
    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `an added entry keeps its cover colour past the browse cap`(type: ContentType) = runTest {
        val tracker = mockk<SourceTrackerDispatcher>(relaxed = true)
        val entry = when (type) {
            ContentType.MANGA -> EntryId.Manga(ID)
            else -> EntryId.Novel(ID)
        }
        CoverColors.put(entry.signedKey(), COLOR, inLibrary = false)

        when (type) {
            ContentType.MANGA -> UpdateManga(
                mockk<MangaRepository> {
                    coEvery { update(any()) } returns true
                },
                mockk(),
                tracker,
            )
                .awaitUpdateFavorite(ID, favorite = true)
            else -> UpdateNovel(mockk<NovelRepository> { coEvery { update(any<NovelUpdate>()) } returns true }, tracker)
                .awaitUpdateFavorite(ID, favorite = true)
        }
        (1L..CoverColors.CAP).forEach { CoverColors.put(BROWSE_BASE + it, COLOR, inLibrary = false) }

        CoverColors[entry.signedKey()] shouldBe COLOR
    }

    @AfterEach
    fun clearColors() {
        CoverColors.clear()
    }

    private companion object {
        const val COLOR = 0x123456
        const val BROWSE_BASE = 10_000L
        const val ID = 7L
        val NEXT_UPDATE = Instant.fromEpochMilliseconds(1_000_000L)
    }
}
