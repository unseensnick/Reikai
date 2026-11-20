package reikai.domain.migration

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.interactor.UpdateManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mihon.domain.migration.usecases.MigrateMangaUseCase
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.db.SqlDelightTransactions
import reikai.data.library.LibraryHiddenRepositoryImpl
import reikai.data.merge.MergeGroupRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelChapterSyncRules
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.data.novel.NovelTrackRepositoryImpl
import reikai.data.readinglog.ReadingLogRepositoryImpl
import reikai.data.readinglog.ReadingPaceRepositoryImpl
import reikai.domain.category.GetNovelCategories
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.chapter.NoChapterTombstones
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.HideSeries
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaGroupCategories
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelGroupCategories
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.MigrateNovelUseCase
import reikai.domain.novel.interactor.SetNovelCategories
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.interactor.UpsertNovelTrack
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks

/**
 * The hide carry through each type's real migrate use case over real repositories on an in-memory
 * database. Faked boundaries only: the source managers, the cover cache, the download manager, the
 * tracker manager and the source tracker, none of which a migration without flags or a refresh reaches
 * for anything the hide depends on.
 */
class MigrateHiddenRealDbTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a replace migration of a hidden entry hides the target and drops the source's row`(engine: HideEngine) =
        runTest {
            engine.run {
                hide(SOURCE)
                migrate(replace = true)

                hidden() shouldBe listOf(TARGET)
            }
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a replace migration of a hidden group's member flags every library member, target included`(
        engine: HideEngine,
    ) = runTest {
        engine.run {
            group(SOURCE, SIBLING)
            hideSeries.hide(listOf(entry(SIBLING)))
            migrate(replace = true)

            hidden() shouldBe listOf(TARGET, SIBLING)
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `the migrated target leaving the library keeps its group hidden`(engine: HideEngine) = runTest {
        engine.run {
            group(SOURCE, SIBLING)
            hideSeries.hide(listOf(entry(SIBLING)))
            migrate(replace = true)
            leave(TARGET)

            hidden() shouldBe listOf(SIBLING)
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a copy migration of an ungrouped hidden entry leaves the source hidden and the target visible`(
        engine: HideEngine,
    ) = runTest {
        engine.run {
            hide(SOURCE)
            migrate(replace = false)

            hidden() shouldBe listOf(SOURCE)
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a copy migration into a hidden group shows the group it joins`(engine: HideEngine) = runTest {
        engine.run {
            group(SOURCE, SIBLING)
            hide(SOURCE)
            hide(SIBLING)
            migrate(replace = false)

            hidden() shouldBe emptyList()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a replace migration of a visible entry leaves the target visible`(engine: HideEngine) = runTest {
        engine.run {
            migrate(replace = true)

            hidden() shouldBe emptyList()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a replace migration onto a member of another series leaves that series visible`(engine: HideEngine) =
        runTest {
            engine.run {
                hide(SOURCE)
                group(TARGET, OTHER)
                migrate(replace = true)

                hidden() shouldBe emptyList()
            }
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a replace migration of a hidden group onto a member of another series shows the joined group`(
        engine: HideEngine,
    ) = runTest {
        engine.run {
            group(SOURCE, SIBLING)
            hideSeries.hide(listOf(entry(SIBLING)))
            group(TARGET, OTHER)
            migrate(replace = true)

            hidden() shouldBe emptyList()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a replace migration onto a removed member of its own group keeps the series hidden`(engine: HideEngine) =
        runTest {
            engine.run {
                group(SOURCE, TARGET, SIBLING)
                hideSeries.hide(listOf(entry(SIBLING)))
                migrate(replace = true)

                hidden() shouldBe listOf(TARGET, SIBLING)
            }
        }

    companion object {
        const val SOURCE = 1L
        const val TARGET = 2L
        const val SIBLING = 3L
        const val OTHER = 4L

        @JvmStatic
        fun engines() = listOf(MangaHideEngine(), NovelHideEngine())
    }
}

/** One type's real migrate use case; [SOURCE], [SIBLING] and [OTHER] in the library, [TARGET] stored but not. */
abstract class HideEngine(private val type: ContentType) {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    protected lateinit var database: Database
    protected val prefs = ReikaiLibraryPreferences(InMemoryPreferenceStore())
    protected val merges by lazy { MergeGroupRepositoryImpl(database) }
    protected val hiddenRepo by lazy { LibraryHiddenRepositoryImpl(database) }
    val hideSeries by lazy {
        HideSeries(hiddenRepo, MangaMergeManager(merges, prefs, {}, {}), NovelMergeManager(merges, prefs, {}, {}))
    }

    suspend fun run(block: suspend HideEngine.() -> Unit) {
        Database.Schema.create(driver).await()
        driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
        database = DatabaseBindings.providesDatabase(driver)
        seed(MigrateHiddenRealDbTest.SOURCE, favourite = true)
        seed(MigrateHiddenRealDbTest.TARGET, favourite = false)
        seed(MigrateHiddenRealDbTest.SIBLING, favourite = true)
        seed(MigrateHiddenRealDbTest.OTHER, favourite = true)
        try {
            block()
        } finally {
            driver.close()
        }
    }

    protected suspend fun exec(sql: String) = driver.execute(null, sql, 0).await()

    protected abstract suspend fun seed(id: Long, favourite: Boolean)
    abstract suspend fun migrate(replace: Boolean)
    abstract suspend fun hidden(): List<Long>
    abstract suspend fun leave(id: Long)

    suspend fun group(vararg ids: Long) {
        merges.createGroup(type, ids.toList())
    }

    suspend fun hide(id: Long) = hiddenRepo.hide(type, listOf(id))

    fun entry(id: Long) = EntryId.of(type, id)
}

class MangaHideEngine : HideEngine(ContentType.MANGA) {
    override fun toString() = "manga"

    override suspend fun seed(id: Long, favourite: Boolean) = exec(
        "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
            "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
            "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
            "($id, 1, 'm$id', 'manga', 0, 0, 0, 0, 0, ${if (favourite) "1000" else "NULL"}, 0, 0, '', '{}')",
    ).let { }

    override suspend fun migrate(replace: Boolean) {
        val mangas = MangaRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database)
        val history = HistoryRepositoryImpl(database)
        val tracks = TrackRepositoryImpl(database)
        // Stubbed outside a mockk block, where get binds to the matcher scope.
        val sourceManager = mockk<SourceManager>()
        coEvery { sourceManager.get(any()) } returns mockk(relaxed = true)
        val mergeManager = MangaMergeManager(merges, prefs, {}, {})
        val useCase = MigrateMangaUseCase(
            scope = CoroutineScope(Dispatchers.Unconfined),
            sourcePreferences = mockk(relaxed = true),
            trackerManager = mockk(relaxed = true),
            sourceManager = sourceManager,
            downloadManager = mockk(relaxed = true),
            updateManga = UpdateManga(mangas, FetchInterval(GetChaptersByMangaId(chapters)), mockk(relaxed = true)),
            getChaptersByMangaId = GetChaptersByMangaId(chapters),
            updateChapter = UpdateChapter(chapters),
            getCategories = GetCategories(CategoryRepositoryImpl(database)),
            getTracks = GetTracks(tracks),
            trackRepository = tracks,
            coverCache = mockk(relaxed = true),
            updateMangaFromRemote = mockk(),
            mangaMergeManager = mergeManager,
            chapterRepository = chapters,
            transactions = SqlDelightTransactions(database),
            swapEffects = MigrationSwapEffects(
                MangaGroupCategories(GetCategories(CategoryRepositoryImpl(database)), SetMangaCategories(mangas)),
                mockk(),
                mergeManager,
                mockk(),
                mockk(relaxed = true),
            ),
            getHistory = GetHistory(history),
            historyRepository = history,
            readingLog = ReadingLogRepositoryImpl(database),
            hideSeries = hideSeries,
            carryCustomInfo = mockk(relaxed = true),
        )
        val current = checkNotNull(mangas.getMangaById(MigrateHiddenRealDbTest.SOURCE))
        val target = checkNotNull(mangas.getMangaById(MigrateHiddenRealDbTest.TARGET))
        useCase(current, target, replace, emptySet(), skipTargetRefresh = true)
    }

    override suspend fun hidden() = database.library_hiddenQueries.selectAllManga().awaitAsList().sorted()

    override suspend fun leave(id: Long) {
        MangaRepositoryImpl(database).update(MangaUpdate(id) { favoriteAt = null })
    }
}

class NovelHideEngine : HideEngine(ContentType.NOVELS) {
    override fun toString() = "novel"

    private val novels by lazy { NovelRepositoryImpl(database) }

    override suspend fun seed(id: Long, favourite: Boolean) = exec(
        "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
            "VALUES ($id, 'src', 'n$id', 'novel', 0, 0, 0, ${if (favourite) "1000" else "NULL"})",
    ).let { }

    override suspend fun migrate(replace: Boolean) {
        val tracks = NovelTrackRepositoryImpl(database)
        val mergeManager = NovelMergeManager(merges, prefs, {}, {})
        val sourceManager = mockk<NovelSourceManager>()
        coEvery { sourceManager.get(any<String>()) } returns mockk(relaxed = true)
        val useCase = MigrateNovelUseCase(
            novelChapterRepository = NovelChapterRepositoryImpl(database),
            getNovelCategories = GetNovelCategories(CategoryRepositoryImpl(database)),
            novelMergeManager = mergeManager,
            novelDownloadManagerProvider = { error("a migration without flags or a refresh needs no downloads") },
            updateNovel = UpdateNovel(novels, mockk(relaxed = true)),
            coverCache = mockk(relaxed = true),
            getNovelTracks = GetNovelTracks(tracks, mergeManager, prefs),
            upsertNovelTrack = UpsertNovelTrack(tracks),
            sourceManager = sourceManager,
            novelRepository = novels,
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            syncRules = NovelChapterSyncRules(
                NoChapterNumberOverrides,
                InMemoryChapterCopyMarks(),
                NoChapterTombstones,
            ),
            transactions = SqlDelightTransactions(database),
            swapEffects = MigrationSwapEffects(
                mockk(),
                NovelGroupCategories(GetNovelCategories(CategoryRepositoryImpl(database)), SetNovelCategories(novels)),
                mockk(),
                mergeManager,
                mockk(relaxed = true),
            ),
            novelHistoryRepository = NovelHistoryRepositoryImpl(database),
            readingLog = ReadingLogRepositoryImpl(database),
            readingPace = ReadingPaceRepositoryImpl(database),
            hideSeries = hideSeries,
            carryCustomInfo = mockk(relaxed = true),
        )
        val current = checkNotNull(novels.getById(MigrateHiddenRealDbTest.SOURCE))
        val target = checkNotNull(novels.getById(MigrateHiddenRealDbTest.TARGET))
        useCase(current, target, emptySet(), replace, skipTargetRefresh = true)
    }

    override suspend fun hidden() = database.library_hiddenQueries.selectAllNovel().awaitAsList().sorted()

    override suspend fun leave(id: Long) {
        novels.update(NovelUpdate(id) { favoriteAt = null })
    }
}
