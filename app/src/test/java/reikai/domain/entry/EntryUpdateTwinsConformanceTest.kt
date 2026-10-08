package reikai.domain.entry

import eu.kanade.domain.manga.interactor.UpdateManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reikai.data.novel.expectedNextUpdate
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.track.source.SourceTrackerDispatcher
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

/** The novel halves of two of Mihon's manga rules, each run beside the manga original. */
class EntryUpdateTwinsConformanceTest {

    @ParameterizedTest(name = "status {0}")
    @ValueSource(longs = [1L, 2L])
    fun `an entry is next due when manga's rule says, and never once completed`(status: Long) {
        val novel = Novel.create().copy(status = status, nextUpdate = NEXT_UPDATE)
        val manga = Manga.create().copy(status = status, nextUpdate = NEXT_UPDATE)

        novel.expectedNextUpdate() shouldBe manga.expectedNextUpdate
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

    private companion object {
        const val ID = 7L
        const val NEXT_UPDATE = 1_000_000L
    }
}
