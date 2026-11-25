package reikai.domain.entry

import eu.kanade.domain.manga.interactor.UpdateManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
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

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `an entry never predicted is never due`(kind: Kind) {
        kind.expectedNextUpdate(null) shouldBe null
    }

    @ParameterizedTest(name = "{0} favorite {1}")
    @MethodSource("kindsAndFavorites")
    fun `adding and removing stamp the join date and tell the source's tracker`(kind: Kind, favorite: Boolean) =
        runTest {
            val told = mutableListOf<Boolean>()
            val tracker = mockk<SourceTrackerDispatcher> {
                every { favoriteChanged(any(), any()) } answers { told += secondArg<Boolean>() }
            }

            val (stamped, joined) = kind.writeFavorite(favorite, tracker)

            stamped shouldBe true
            (joined != null) shouldBe favorite
            told shouldBe listOf(favorite)
        }

    /** Each type's favourite write, as (whether it sets the join date, the date it sets). */
    enum class Kind(
        val expectedNextUpdate: (Instant?) -> Instant?,
        val writeFavorite: suspend (Boolean, SourceTrackerDispatcher) -> Pair<Boolean, Instant?>,
    ) {
        MANGA(
            { Manga.create().copy(nextUpdate = it).expectedNextUpdate },
            { favorite, tracker ->
                val write = slot<MangaUpdate>()
                val repository = mockk<MangaRepository> { coEvery { update(capture(write)) } returns true }
                UpdateManga(repository, mockk(), tracker).awaitUpdateFavorite(ID, favorite)
                write.captured.isSet(MangaUpdate::favoriteAt) to write.captured.favoriteAt
            },
        ),
        NOVEL(
            { Novel.create().copy(nextUpdate = it).expectedNextUpdate() },
            { favorite, tracker ->
                val write = slot<NovelUpdate>()
                val repository = mockk<NovelRepository> { coEvery { update(capture(write)) } returns true }
                UpdateNovel(repository, tracker).awaitUpdateFavorite(ID, favorite)
                write.captured.isSet(NovelUpdate::favoriteAt) to write.captured.favoriteAt
            },
        ),
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

    companion object {
        @JvmStatic
        fun kindsAndFavorites() = Kind.entries.flatMap { kind -> listOf(true, false).map { Arguments.of(kind, it) } }

        private const val COLOR = 0x123456
        private const val BROWSE_BASE = 10_000L
        private const val ID = 7L
        private val NEXT_UPDATE = Instant.fromEpochMilliseconds(1_000_000L)
    }
}
