package reikai.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.merge.MergeGroupRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.library.ContentType
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
import tachiyomi.domain.manga.model.Manga

/**
 * A merge group member removed from the library is written into the backup as an entry of its own, for
 * both content types, even with "All read entries" off and nothing read. The group itself is always
 * written, and a restore resolves a member only against a row it restored, so without the entry a
 * fresh install loses the member, and a two-member group loses the whole group.
 */
class GroupMemberBackupConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
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
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a group member outside the library is backed up once, as outside the library`(type: ContentType) =
        runTest {
            entries(type) shouldBe listOf("url-1" to true, "url-2" to false)
        }

    /** Backs up a group of entries 1 and 2 after 2 left the library, as (url, favorite) per entry. */
    private suspend fun entries(type: ContentType): List<Pair<String, Boolean>> {
        val table = if (type == ContentType.MANGA) "mangas" else "novels"
        listOf(1L, 2L).forEach { id ->
            val insert = if (type == ContentType.MANGA) {
                "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                    "chapter_flags, cover_last_modified, date_added) " +
                    "VALUES ($id, 1, 'url-$id', 'title', 0, 1, 0, 0, 0, 0, 0)"
            } else {
                "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags) " +
                    "VALUES ($id, 'src', 'url-$id', 'title', 0, 1, 0, 0)"
            }
            driver.execute(null, insert, 0).await()
        }
        val groups = MergeGroupRepositoryImpl(database)
        groups.createGroup(type, listOf(1L, 2L))!!
        driver.execute(null, "UPDATE $table SET favorite = 0 WHERE _id = 2", 0).await()

        val options = BackupOptions(
            readEntries = false,
            chapters = false,
            categories = false,
            tracking = false,
            history = false,
            customInfo = false,
        )
        return if (type == ContentType.MANGA) {
            val creator = MangaBackupCreator(
                database = database,
                getCategories = mockk(),
                getHistory = mockk(),
                mangaMetadataRepository = mockk { coEvery { getMetadataById(any()) } returns null },
                customMangaInfoRepository = mockk(),
                getFavorites = mockk {
                    coEvery { await() } returns
                        listOf(Manga.create().copy(id = 1, url = "url-1", source = 1L, favorite = true))
                },
                mangaRepository = mockk(),
                mergeGroupRepository = groups,
            )
            options.backupEntries(creator).toList().map { it.url to it.favorite }
        } else {
            val novels = NovelRepositoryImpl(database)
            val creator = NovelBackupCreator(
                novelRepository = novels,
                novelChapterRepository = mockk(),
                categoryRepository = mockk(),
                novelTrackRepository = mockk(),
                mergeGroupRepository = groups,
                customNovelInfoRepository = mockk(),
                database = database,
            )
            options.backupEntries(creator).toList().map { it.url to it.favorite }
        }
    }
}
