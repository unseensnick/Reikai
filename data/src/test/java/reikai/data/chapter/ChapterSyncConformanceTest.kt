package reikai.data.chapter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.domain.novel.model.NovelChapter
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
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter

/**
 * A source sync adds a chapter only when the entry does not already have one at that url, read inside
 * the sync's own transaction, so two syncs racing on one entry cannot store it twice (mihon 6ee529c5a).
 */
class ChapterSyncConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = Database(
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
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter the entry already has is not added again`(type: Type) = runTest {
        type.seed(database, url = "c")

        type.sync(database, urls = listOf("c", "d"))

        type.urls(database) shouldBe listOf("c", "d")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a sync reports only the chapters it added`(type: Type) = runTest {
        type.seed(database, url = "c")

        type.sync(database, urls = listOf("c", "d")) shouldBe listOf("d")
    }

    enum class Type {
        MANGA {
            override suspend fun seed(database: Database, url: String) {
                database.mangasQueries.insertReturningId(
                    source = 1, url = "m", artist = null, author = null, description = null, genre = null,
                    title = "T", status = 0, thumbnailUrl = null, favoriteAt = 0, lastUpdate = 0, nextUpdate = 0,
                    initialized = false, viewerFlags = 0, chapterFlags = 0, coverLastModified = 0,
                    updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                    calculateInterval = 0, notes = "", memo = JsonObject(emptyMap()),
                )
                sync(database, listOf(url))
            }

            override suspend fun sync(database: Database, urls: List<String>) = ChapterRepositoryImpl(database)
                .updateFromRemote(
                    emptyList(),
                    urls.map {
                        Chapter.create().copy(mangaId = 1, url = it, name = it)
                    },
                    emptyList(),
                )
                .map { it.url }

            override suspend fun urls(database: Database) =
                ChapterRepositoryImpl(database).getChapterByMangaId(1).map { it.url }.sorted()
        },
        NOVEL {
            override suspend fun seed(database: Database, url: String) {
                database.novelsQueries.insert(
                    source = "src", url = "n", title = "T", author = null, artist = null, description = null,
                    genre = null, status = 0, thumbnailUrl = null, favoriteAt = 0, lastUpdate = 0, initialized = false,
                    chapterFlags = 0, updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                    coverLastModified = 0, totalPages = 1, notes = "", viewerFlags = 0,
                )
                sync(database, listOf(url))
            }

            override suspend fun sync(database: Database, urls: List<String>) = NovelChapterRepositoryImpl(database)
                .updateFromRemote(emptyList(), urls.map { chapter(it) }, emptyList())
                .map { it.url }

            override suspend fun urls(database: Database) =
                NovelChapterRepositoryImpl(database).getByNovelId(1).map { it.url }.sorted()

            private fun chapter(url: String) = NovelChapter(
                id = -1L, novelId = 1L, url = url, name = url, read = false, bookmark = false,
                lastTextProgress = 0L, chapterNumber = 1.0, sourceOrder = 0L, dateFetch = 0L,
                dateUpload = 0L, page = "",
            )
        }, ;

        abstract suspend fun seed(database: Database, url: String)

        abstract suspend fun sync(database: Database, urls: List<String>): List<String>

        abstract suspend fun urls(database: Database): List<String>
    }
}
