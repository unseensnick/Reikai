package reikai.domain.track

import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.entry.EntryId
import reikai.domain.manga.DeleteTrackInGroup
import reikai.domain.manga.GetTracksInGroup
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.AddNovelTrack
import reikai.domain.novel.interactor.DeleteNovelTrack
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.RefreshNovelTracks
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.track.NovelTrackUpdater
import reikai.domain.track.autobind.AutoBindEntry
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.model.Track

/**
 * The tracking sheet reaches each content type's engine only through [EntryTrackPorts.of], so the
 * per-type choices it used to make on an isNovel flag are pinned here once, over both ports.
 */
class EntryTrackPortConformanceTest {

    enum class Type(val entry: EntryId, val catalogue: String) {
        MANGA(EntryId.Manga(ENTRY_ID), MANGA_CATALOGUE) {
            override fun expectedWriter(h: Harness): TrackWriter = MangaTrackWriter
            override fun expectedAutoBind(h: Harness): AutoBindEntry = AutoBindEntry.Manga(h.manga, h.mangaSource)
        },
        NOVEL(EntryId.Novel(ENTRY_ID), NOVEL_CATALOGUE) {
            override fun expectedWriter(h: Harness): TrackWriter = h.novelTrackUpdater
            override fun expectedAutoBind(h: Harness): AutoBindEntry = AutoBindEntry.Novel(h.novel, h.novelSource)
        },
        ;

        abstract fun expectedWriter(h: Harness): TrackWriter
        abstract fun expectedAutoBind(h: Harness): AutoBindEntry

        val other: Type get() = entries.single { it != this }
    }

    /** Every engine call the ports can make, recorded by name, entry and tracker. */
    class Harness {
        val calls = mutableListOf<String>()

        val manga = Manga.create().copy(id = ENTRY_ID, source = SOURCE_ID)
        val mangaSource = mockk<Source>()
        val novel = Novel.create().copy(id = ENTRY_ID, source = SOURCE_ID.toString())
        val novelSource = mockk<NovelSource>()
        val novelTrackUpdater = mockk<NovelTrackUpdater>()

        private val getTracksInGroup = mockk<GetTracksInGroup> {
            every { subscribe(ENTRY_ID) } returns flowOf(listOf(mangaTrack(GROUP_ROW)))
        }
        private val getNovelTracks = mockk<GetNovelTracks> {
            every { subscribe(ENTRY_ID) } returns flowOf(listOf(novelTrack(OWN_ROW)))
            every { subscribeGroup(ENTRY_ID) } returns flowOf(listOf(novelTrack(GROUP_ROW)))
        }
        private val deleteTrackInGroup = mockk<DeleteTrackInGroup> {
            coEvery { await(any(), any()) } answers
                { calls += "manga group unbind ${firstArg<Long>()} ${secondArg<Long>()}" }
        }
        private val deleteNovelTrack = mockk<DeleteNovelTrack> {
            coEvery { await(any(), any()) } answers
                { calls += "novel row unbind ${firstArg<Long>()} ${secondArg<Long>()}" }
            coEvery { awaitGroup(any(), any()) } answers {
                calls += "novel group unbind ${firstArg<Long>()} ${secondArg<Long>()}"
            }
        }
        private val addNovelTrack = mockk<AddNovelTrack> {
            coEvery { bind(any(), any(), any()) } answers { calls += "novel bind ${thirdArg<Long>()}" }
        }

        val ports = EntryTrackPorts(
            getTracksInGroup = getTracksInGroup,
            getManga = mockk { coEvery { await(ENTRY_ID) } returns manga },
            sourceManager = mockk {
                coEvery { getOrStub(any()) } returns mockk()
                coEvery { getOrStub(SOURCE_ID) } returns mangaSource
            },
            refreshTracks = mockk<RefreshTracks>(),
            deleteTrackInGroup = deleteTrackInGroup,
            getNovelTracks = getNovelTracks,
            novelRepository = mockk<NovelRepository> { coEvery { getById(ENTRY_ID) } returns novel },
            novelSourceManager = mockk<NovelSourceManager> {
                coEvery { this@mockk.get(any<String>()) } returns mockk()
                coEvery { this@mockk.get(SOURCE_ID.toString()) } returns novelSource
            },
            refreshNovelTracks = mockk<RefreshNovelTracks>(),
            addNovelTrack = addNovelTrack,
            deleteNovelTrack = deleteNovelTrack,
            novelTrackUpdater = novelTrackUpdater,
        )

        /** A tracker whose catalogues are [catalogues]; each search answers with a hit named for its catalogue. */
        fun tracker(vararg catalogues: String) = mockk<Tracker> {
            every { id } returns TRACKER_ID
            every { supportsManga } returns (MANGA_CATALOGUE in catalogues)
            every { supportsNovels } returns (NOVEL_CATALOGUE in catalogues)
            coEvery { search(QUERY) } returns listOf(hit(MANGA_CATALOGUE))
            coEvery { searchNovel(QUERY) } returns listOf(hit(NOVEL_CATALOGUE))
            coEvery { register(any(), any()) } answers { calls += "manga bind ${secondArg<Long>()}" }
        }
    }

    private val h = Harness()

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a search asks the tracker's catalogue of the entry's own type`(type: Type) = runTest {
        h.ports.of(type.entry).search(h.tracker(MANGA_CATALOGUE, NOVEL_CATALOGUE), QUERY).single().title shouldBe
            type.catalogue
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a tracker holding only the other type's catalogue is not supported`(type: Type) {
        h.ports.of(type.entry).supports(h.tracker(type.other.catalogue)) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a tracker holding the entry's own catalogue is supported`(type: Type) {
        h.ports.of(type.entry).supports(h.tracker(type.catalogue)) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `unbinding clears the tracker across the entry's merge group`(type: Type) = runTest {
        h.ports.of(type.entry).unbindInGroup(TRACKER_ID)

        h.calls shouldBe listOf("${type.catalogue} group unbind $ENTRY_ID $TRACKER_ID")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `binding writes through the entry's own engine`(type: Type) = runTest {
        h.ports.of(type.entry).bind(h.tracker(type.catalogue), hit(type.catalogue))

        h.calls shouldBe listOf("${type.catalogue} bind $ENTRY_ID")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the tracks read spans the merge group`(type: Type) = runTest {
        h.ports.of(type.entry).tracks().first().single().title shouldBe GROUP_ROW
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the auto-bind entry carries the entry's own source`(type: Type) = runTest {
        h.ports.of(type.entry).autoBindEntry() shouldBe type.expectedAutoBind(h)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the writer is the entry's own engine's`(type: Type) {
        h.ports.of(type.entry).writer shouldBe type.expectedWriter(h)
    }

    private companion object {
        const val ENTRY_ID = 7L
        const val SOURCE_ID = 42L
        const val TRACKER_ID = 3L
        const val QUERY = "query"
        const val MANGA_CATALOGUE = "manga"
        const val NOVEL_CATALOGUE = "novel"
        const val GROUP_ROW = "group row"
        const val OWN_ROW = "own row"

        fun hit(title: String) = TrackSearch.create(TRACKER_ID).apply { this.title = title }

        fun mangaTrack(title: String) = Track(
            id = 1L,
            mangaId = ENTRY_ID,
            trackerId = TRACKER_ID,
            remoteId = 9L,
            libraryId = null,
            title = title,
            lastChapterRead = 0.0,
            totalChapters = 0L,
            status = 0L,
            score = 0.0,
            remoteUrl = "",
            startDate = 0L,
            finishDate = 0L,
            private = false,
        )

        fun novelTrack(title: String) = NovelTrack(
            id = 1L,
            novelId = ENTRY_ID,
            trackerId = TRACKER_ID,
            remoteId = 9L,
            libraryId = null,
            title = title,
            lastChapterRead = 0.0,
            totalChapters = 0L,
            status = 0L,
            score = 0.0,
            remoteUrl = "",
            startDate = 0L,
            finishDate = 0L,
            private = false,
        )
    }
}
