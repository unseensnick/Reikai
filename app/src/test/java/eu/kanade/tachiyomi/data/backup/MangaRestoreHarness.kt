package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import io.mockk.mockk
import reikai.domain.db.PassThroughTransactions
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.RestoreMergeGroups
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
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
            "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                "state_initialized, user_reader_flags, user_chapter_flags, " +
                "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                "state_chapter_fetch_interval, user_notes, remote_memo) VALUES ($id, $source, " +
                "'$url', '', 0, 0, 0, 0, 0, NULL, 0, 0, '', '{}')",
            0,
        ).await()
    }

    /** Stores [manga] as the device's copy and returns its id. */
    suspend fun insert(manga: Manga): Long = database.mangaQueries.insertReturningId(
        sourceId = manga.source,
        remoteUrl = manga.url,
        remoteArtist = manga.artist,
        remoteAuthor = manga.author,
        remoteDescription = manga.description,
        remoteGenre = manga.genre,
        remoteTitle = manga.title,
        remoteStatus = manga.status,
        remoteCover = manga.thumbnailUrl,
        userFavoriteAt = manga.favoriteAt,
        stateChapterLastUpdate = manga.lastUpdate,
        stateChapterNextUpdate = manga.nextUpdate,
        stateChapterFetchInterval = manga.fetchInterval.toLong(),
        stateInitialized = manga.initialized,
        userReaderFlags = manga.viewerFlags,
        userChapterFlags = manga.chapterFlags,
        stateCoverLastModified = manga.coverLastModified,
        remoteUpdateStrategy = manga.updateStrategy,
        userNotes = manga.notes,
        remoteMemo = manga.memo,
    ).awaitAsOne()

    /** Stores [chapter] on the device and returns it with its id. */
    suspend fun insert(
        chapter: Chapter,
    ): Chapter = chapters.updateFromRemote(emptyList(), listOf(chapter), emptyList()).single()

    override fun close() = driver.close()

    companion object {
        suspend fun create(): MangaRestoreHarness {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            return MangaRestoreHarness(driver, database)
        }
    }
}
