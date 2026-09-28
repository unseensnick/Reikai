package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import io.mockk.mockk
import reikai.domain.db.PassThroughTransactions
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.RestoreMergeGroups
import tachiyomi.data.Chapters
import tachiyomi.data.Custom_manga_info
import tachiyomi.data.Custom_novel_info
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.Novels
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.backup.RestoreRepositoryImpl
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.CustomMangaInfoRepositoryImpl
import tachiyomi.data.manga.MangaMetadataRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository

/** [MangaRestorer] with every repository production gives it, over [database]. */
fun mangaRestorer(
    database: Database,
    mergeGroups: MergeGroupRepository = mockk(relaxed = true),
    customInfo: CustomMangaInfoRepository = CustomMangaInfoRepositoryImpl(database),
): MangaRestorer {
    val mangas = MangaRepositoryImpl(database)
    val chapters = ChapterRepositoryImpl(database)
    return MangaRestorer(
        restoreRepository = RestoreRepositoryImpl(
            database = database,
            mangaRepository = mangas,
            chapterRepository = chapters,
            trackRepository = TrackRepositoryImpl(database),
            mangaMetadataRepository = MangaMetadataRepositoryImpl(database),
            setCustomMangaInfo = SetCustomMangaInfo(customInfo),
        ),
        getCategories = GetCategories(CategoryRepositoryImpl(database)),
        fetchInterval = FetchInterval(GetChaptersByMangaId(chapters)),
        getMangaByUrlAndSourceId = GetMangaByUrlAndSourceId(mangas),
        restoreMergeGroups = RestoreMergeGroups(mergeGroups, PassThroughTransactions),
        setCustomMangaInfo = SetCustomMangaInfo(customInfo),
    )
}

/**
 * The manga restore over a real in-memory database, from [MangaRestorer] down through the restore
 * repository, with every repository production uses. The restore rules live in the repository since
 * mihon 306befb21, so a test that mocks the queries no longer reaches them.
 */
class MangaRestoreHarness private constructor(val driver: JdbcSqliteDriver, val database: Database) : AutoCloseable {

    val mangas = MangaRepositoryImpl(database)
    val chapters = ChapterRepositoryImpl(database)
    val tracks = TrackRepositoryImpl(database)
    val categories = CategoryRepositoryImpl(database)
    val metadata = MangaMetadataRepositoryImpl(database)
    val customInfo = CustomMangaInfoRepositoryImpl(database)

    fun restorer(
        mergeGroups: MergeGroupRepository = mockk(relaxed = true),
        customInfo: CustomMangaInfoRepository = this.customInfo,
    ) = mangaRestorer(database, mergeGroups, customInfo)

    /** Stores a bare device copy under a chosen [id], for a test that pins the id a restore writes under. */
    suspend fun insertBare(id: Long, url: String, source: Long) {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, initialized, viewer, chapter_flags, " +
                "cover_last_modified, favorite_at) VALUES ($id, $source, '$url', '', 0, 0, 0, 0, 0, NULL)",
            0,
        ).await()
    }

    /** Stores [manga] as the device's copy and returns its id. */
    suspend fun insert(manga: Manga): Long = database.mangasQueries.insertReturningId(
        source = manga.source,
        url = manga.url,
        artist = manga.artist,
        author = manga.author,
        description = manga.description,
        genre = manga.genre,
        title = manga.title,
        status = manga.status,
        thumbnailUrl = manga.thumbnailUrl,
        favoriteAt = manga.favoriteAt,
        lastUpdate = manga.lastUpdate,
        nextUpdate = manga.nextUpdate,
        calculateInterval = manga.fetchInterval.toLong(),
        initialized = manga.initialized,
        viewerFlags = manga.viewerFlags,
        chapterFlags = manga.chapterFlags,
        coverLastModified = manga.coverLastModified,
        updateStrategy = manga.updateStrategy,
        notes = manga.notes,
        memo = manga.memo,
    ).awaitAsOne()

    /** Stores [chapter] on the device and returns it with its id. */
    suspend fun insert(chapter: Chapter): Chapter = chapters.addAll(listOf(chapter)).single()

    override fun close() = driver.close()

    companion object {
        suspend fun create(): MangaRestoreHarness {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = Database(
                driver = driver,
                historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                    memoAdapter = MemoColumnAdapter,
                ),
                chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
                novelsAdapter = Novels.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                ),
                custom_manga_infoAdapter = Custom_manga_info.Adapter(genreAdapter = StringListColumnAdapter),
                custom_novel_infoAdapter = Custom_novel_info.Adapter(genreAdapter = StringListColumnAdapter),
            )
            return MangaRestoreHarness(driver, database)
        }
    }
}
