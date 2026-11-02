package reikai.domain.track

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.merge.MergeGroupRepositoryImpl
import reikai.data.merge.MergedChapterUnitRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaGroupStitcher
import reikai.domain.manga.MangaMergeManager
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.novel.NovelGroupStitcher
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.track.NovelDelayedTrackingStore
import reikai.domain.novel.track.TrackNovelChapter
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.model.Track

/**
 * The number a push sends for a chapter of a merged series is the trunk's copy's, for both content
 * types, over real SQL and the real stitchers. Novels are the reason: two sources number one chapter
 * apart, so a sibling's copy, read under its source chip or resumed from History, pushed a number from
 * the wrong list. Manga pairs by number narrowed to Float, so its trunk copy differs only in that
 * narrowing, which the cases use to make the manga half able to fail too.
 */
class TrackedChapterNumberConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var groups: MergeGroupRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            database = DatabaseBindings.providesDatabase(driver)
            groups = MergeGroupRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `a sibling's copy of a chapter the trunk has pushes the trunk's number`(side: Side) = runTest {
        pairedGroup(side)

        side.tracked(this@TrackedChapterNumberConformanceTest, SIBLING_COPY) shouldBe side.trunkNumber(3)
    }

    /** A preferred source takes the trunk though it has fewer chapters, so its numbers are the ones pushed. */
    @ParameterizedTest
    @EnumSource(Side::class)
    fun `a preferred source's copy gives the number a merged chapter pushes`(side: Side) = runTest {
        pairedGroup(side)
        preferences = ReikaiLibraryPreferences(
            InMemoryPreferenceStore(
                sequenceOf(InMemoryPreference(side.preferredKey, listOf(side.sourceOf(SIBLING)), emptyList<Any>())),
            ),
        )

        side.tracked(this@TrackedChapterNumberConformanceTest, TRUNK_COPY) shouldBe side.siblingNumber(3)
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `a trunk chapter pushes its own number`(side: Side) = runTest {
        pairedGroup(side)

        side.tracked(this@TrackedChapterNumberConformanceTest, TRUNK_COPY) shouldBe side.trunkNumber(3)
    }

    /** A chapter only siblings have: the first sibling's copy shows, the second's must still keep its own. */
    @ParameterizedTest
    @EnumSource(Side::class)
    fun `a copy of a chapter the trunk lacks pushes its own number`(side: Side) = runTest {
        side.entry(this@TrackedChapterNumberConformanceTest, TRUNK, (1..5).map { side.trunkChapter(TRUNK, it) })
        side.entry(
            this@TrackedChapterNumberConformanceTest,
            FIRST_SIBLING,
            (1..3).map { side.siblingChapter(FIRST_SIBLING, it) } + side.gapChapter(FIRST_SIBLING, first = true),
        )
        side.entry(
            this@TrackedChapterNumberConformanceTest,
            SECOND_SIBLING,
            (1..2).map { side.siblingChapter(SECOND_SIBLING, it) } + side.gapChapter(SECOND_SIBLING, first = false),
        )
        groups.createGroup(side.type, listOf(TRUNK, FIRST_SIBLING, SECOND_SIBLING))

        side.tracked(this@TrackedChapterNumberConformanceTest, chapterId(SECOND_SIBLING, GAP)) shouldBe
            side.gapNumber(first = false)
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `a chapter of an unmerged series pushes its own number`(side: Side) = runTest {
        side.entry(this@TrackedChapterNumberConformanceTest, SIBLING, (1..3).map { side.siblingChapter(SIBLING, it) })

        side.tracked(this@TrackedChapterNumberConformanceTest, SIBLING_COPY) shouldBe side.siblingNumber(3)
    }

    /** The delayed queue keeps only a row and a number, so the number has to be converted before it. */
    @ParameterizedTest
    @EnumSource(Side::class)
    fun `a refused push of a sibling's copy queues the trunk's number`(side: Side) = runTest {
        pairedGroup(side)

        val store = side.newStore()

        side.pushRefused(this@TrackedChapterNumberConformanceTest, SIBLING_COPY, store)

        verify { store.add(TRACK_ID, side.trunkNumber(3)) }
    }

    private suspend fun pairedGroup(side: Side) {
        side.entry(this, TRUNK, (1..5).map { side.trunkChapter(TRUNK, it) })
        side.entry(this, SIBLING, (1..3).map { side.siblingChapter(SIBLING, it) })
        groups.createGroup(side.type, listOf(TRUNK, SIBLING))
    }

    /** One chapter row: its id, name and number. */
    class Row(val id: Long, val name: String, val number: Double)

    enum class Side(val type: ContentType) {
        MANGA(ContentType.MANGA) {
            override val preferredKey = "preferred_manga_sources"

            override fun sourceOf(entry: Long): Any = 100 + entry

            // A parsed number is a double and a source-reported one a float, which the stitch pairs.
            override fun trunkNumber(n: Int) = n + 0.1

            override fun siblingNumber(n: Int) = (n + 0.1).toFloat().toDouble()

            override fun gapNumber(first: Boolean) = if (first) 9.1 else 9.1f.toDouble()

            override suspend fun entry(t: TrackedChapterNumberConformanceTest, id: Long, rows: List<Row>) {
                t.exec(
                    "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                        "state_initialized, user_reader_flags, user_chapter_flags, " +
                        "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                        "state_chapter_fetch_interval, user_notes, remote_memo) VALUES ($id, ${100 + id}, " +
                        "'m$id', 'title', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                )
                rows.forEachIndexed { order, row ->
                    t.exec(
                        "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, " +
                            "user_read, user_bookmark, user_last_page_read, remote_chapter_number, " +
                            "remote_order, state_date_fetch, remote_date_upload, remote_memo) VALUES (${row.id}, " +
                            "$id, '/${row.id}', '${row.name}', NULL, 0, 0, 0, ${row.number}, $order, 0, 0, '{}')",
                    )
                }
            }

            override suspend fun tracked(t: TrackedChapterNumberConformanceTest, chapterId: Long): Double =
                t.subject().ofManga(listOf(ChapterRepositoryImpl(t.database).getChapterById(chapterId)!!)).single()

            override suspend fun pushRefused(
                t: TrackedChapterNumberConformanceTest,
                chapterId: Long,
                store: DelayedTrackingStore,
            ) {
                val chapter = ChapterRepositoryImpl(t.database).getChapterById(chapterId)!!
                TrackChapter(
                    getTracks = mockk {
                        coEvery { await(chapter.mangaId) } returns listOf(mangaTrack(chapter.mangaId))
                    },
                    trackerManager = refusingTracker(),
                    upsertTrack = mockk(relaxed = true),
                    delayedTrackingStore = store,
                ).await(
                    mockk(),
                    chapter.mangaId,
                    t.subject().ofManga(listOf(chapter)).single(),
                    setupJobOnFailure = false,
                )
            }

            override fun newStore(): DelayedTrackingStore = mockk(relaxed = true)
        },
        NOVELS(ContentType.NOVELS) {
            override val preferredKey = "preferred_novel_sources"

            override fun sourceOf(entry: Long): Any = "${100 + entry}"

            // Each source numbers its own list, here a hundred apart.
            override fun trunkNumber(n: Int) = n.toDouble()

            override fun siblingNumber(n: Int) = n + 100.0

            override fun gapNumber(first: Boolean) = if (first) 109.0 else 209.0

            override suspend fun entry(t: TrackedChapterNumberConformanceTest, id: Long, rows: List<Row>) {
                t.exec(
                    "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                        "favorite_at) VALUES ($id, '${100 + id}', 'n$id', 'title', 0, 0, 0, 0)",
                )
                rows.forEachIndexed { order, row ->
                    t.exec(
                        "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, chapter_number, " +
                            "source_order, date_fetch, date_upload) " +
                            "VALUES (${row.id}, $id, '/${row.id}', '${row.name}', 0, 0, ${row.number}, $order, 0, 0)",
                    )
                }
            }

            override suspend fun tracked(t: TrackedChapterNumberConformanceTest, chapterId: Long): Double =
                t.subject().ofNovels(listOf(NovelChapterRepositoryImpl(t.database).getById(chapterId)!!)).single()

            override suspend fun pushRefused(
                t: TrackedChapterNumberConformanceTest,
                chapterId: Long,
                store: DelayedTrackingStore,
            ) {
                val chapter = NovelChapterRepositoryImpl(t.database).getById(chapterId)!!
                TrackNovelChapter(
                    getNovelTracks = mockk {
                        coEvery { awaitGroup(chapter.novelId) } returns listOf(novelTrack(chapter.novelId))
                    },
                    trackerManager = refusingTracker(),
                    upsertNovelTrack = mockk(relaxed = true),
                    delayedTrackingStore = store as NovelDelayedTrackingStore,
                ).await(
                    mockk(),
                    chapter.novelId,
                    t.subject().ofNovels(listOf(chapter)).single(),
                    setupJobOnFailure = false,
                )
            }

            override fun newStore(): DelayedTrackingStore = mockk<NovelDelayedTrackingStore>(relaxed = true)
        },
        ;

        /** The preference holding this type's source ranking. */
        abstract val preferredKey: String

        abstract fun sourceOf(entry: Long): Any

        abstract fun trunkNumber(n: Int): Double

        abstract fun siblingNumber(n: Int): Double

        /** The number of the chapter only the siblings have, on the first sibling or the second. */
        abstract fun gapNumber(first: Boolean): Double

        abstract suspend fun entry(t: TrackedChapterNumberConformanceTest, id: Long, rows: List<Row>)

        abstract suspend fun tracked(t: TrackedChapterNumberConformanceTest, chapterId: Long): Double

        /** Pushes [chapterId] as read, the way every push site converts it, to a tracker that refuses. */
        abstract suspend fun pushRefused(
            t: TrackedChapterNumberConformanceTest,
            chapterId: Long,
            store: DelayedTrackingStore,
        )

        abstract fun newStore(): DelayedTrackingStore

        fun trunkChapter(entry: Long, n: Int) = Row(chapterId(entry, n), "Chapter $n: Title $n", trunkNumber(n))

        fun siblingChapter(entry: Long, n: Int) = Row(chapterId(entry, n), "Chapter $n: Title $n", siblingNumber(n))

        fun gapChapter(entry: Long, first: Boolean) = Row(chapterId(entry, GAP), "Chapter 9: Title 9", gapNumber(first))
    }

    private var preferences = ReikaiLibraryPreferences(InMemoryPreferenceStore())

    private fun subject(): TrackedChapterNumber {
        val mangaMerge = MangaMergeManager(groups, preferences, onMerged = {}) {}
        val novelMerge = NovelMergeManager(groups, preferences, onMerged = {}) {}
        val mangaChapters = ChapterRepositoryImpl(database)
        val stitchers = setOf(
            MangaGroupStitcher(
                groups,
                GetMangaWithChapters(MangaRepositoryImpl(database), mangaChapters),
                mangaMerge,
                mockk<SourceManager> { coEvery { get(any<Long>()) } returns null },
                preferences,
            ),
            NovelGroupStitcher(
                groups,
                NovelRepositoryImpl(database),
                NovelChapterRepositoryImpl(database),
                novelMerge,
                preferences,
            ),
        )
        return TrackedChapterNumber(
            ReconcileMergedChapters(MergedChapterUnitRepositoryImpl(database), stitchers),
            stitchers,
            mangaMerge,
            novelMerge,
        )
    }

    private suspend fun exec(sql: String) {
        driver.execute(null, sql, 0).await()
    }

    private companion object {
        // The trunk lists the most chapters, so it leads with no ranking set.
        const val TRUNK = 1L
        const val SIBLING = 2L
        const val FIRST_SIBLING = 2L
        const val SECOND_SIBLING = 3L
        const val GAP = 9
        const val TRACK_ID = 1L
        const val TRACKER_ID = 3L

        fun chapterId(entry: Long, n: Int) = entry * 100 + n

        val TRUNK_COPY = chapterId(TRUNK, 3)
        val SIBLING_COPY = chapterId(SIBLING, 3)

        fun refusingTracker(): TrackerManager {
            val tracker = mockk<BaseTracker> {
                every { id } returns TRACKER_ID
                every { isLoggedIn } returns true
                every { supportsReadingDates } returns false
                coEvery { refresh(any()) } answers { firstArg() }
                coEvery { update(any(), any()) } throws HttpException(429)
            }
            return mockk { every { get(TRACKER_ID) } returns tracker }
        }

        fun mangaTrack(mangaId: Long) = Track(
            id = TRACK_ID,
            mangaId = mangaId,
            trackerId = TRACKER_ID,
            remoteId = 9L,
            libraryId = null,
            title = "title",
            lastChapterRead = 0.0,
            totalChapters = 0L,
            status = 0L,
            score = 0.0,
            remoteUrl = "",
            startDate = null,
            finishDate = null,
            private = false,
        )

        fun novelTrack(novelId: Long) = NovelTrack(
            id = TRACK_ID,
            novelId = novelId,
            trackerId = TRACKER_ID,
            remoteId = 9L,
            libraryId = null,
            title = "title",
            lastChapterRead = 0.0,
            totalChapters = 0L,
            status = 0L,
            score = 0.0,
            remoteUrl = "",
            startDate = null,
            finishDate = null,
            private = false,
        )
    }
}
