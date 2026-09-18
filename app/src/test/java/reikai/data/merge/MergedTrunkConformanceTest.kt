package reikai.data.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.library.ContentType
import reikai.domain.manga.ChapterAggregation
import reikai.domain.novel.NovelChapterAggregation
import reikai.presentation.library.MangaMergeCollapse
import reikai.presentation.library.novels.NovelMergeCollapse
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
import tachiyomi.data.manga.MangaRepositoryImpl

/**
 * The collapsed library row leads on the member the stitch makes the trunk, for both content types:
 * its cover, title and badge are that member's, and the details list opens on the stitch. Each type
 * ranks through the same source-priority kernel, then a chapter count, and the library has to read
 * the count the stitch ranks on. Runs the library's own rows and counts from real SQL, with no stitch
 * stored, which is how every group starts.
 */
class MergedTrunkConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var groups: MergeGroupRepositoryImpl

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
            groups = MergeGroupRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `with no ranking set the library leads on the stitch's trunk`(type: ContentType) = runTest {
        val side = side(type)
        // The larger member lists most of its chapters under a scanlator the user hides, which is a
        // display choice the stitch does not see. Novels have no scanlators, so theirs are all shown.
        side.entry(id = LARGER, source = 100L, chapters = 10, hidden = 8)
        side.entry(id = SMALLER, source = 200L, chapters = 5)
        groups.createGroup(type, listOf(LARGER, SMALLER))

        side.libraryPrimary(preferred = emptyList()) shouldBe side.stitchTrunk(preferred = emptyList())
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a preferred source leads the library as it leads the stitch`(type: ContentType) = runTest {
        val side = side(type)
        side.entry(id = LARGER, source = 100L, chapters = 10)
        side.entry(id = SMALLER, source = 200L, chapters = 5)
        groups.createGroup(type, listOf(LARGER, SMALLER))

        side.libraryPrimary(preferred = listOf(200L)) shouldBe side.stitchTrunk(preferred = listOf(200L))
    }

    private fun side(type: ContentType): Side = if (type == ContentType.NOVELS) NovelSide() else MangaSide()

    private interface Side {
        suspend fun entry(id: Long, source: Long, chapters: Int, hidden: Int = 0)

        suspend fun libraryPrimary(preferred: List<Long>): Long

        suspend fun stitchTrunk(preferred: List<Long>): Long
    }

    private inner class MangaSide : Side {
        private val members = mutableMapOf<Long, Long>()

        override suspend fun entry(id: Long, source: Long, chapters: Int, hidden: Int) {
            members[id] = source
            exec(
                "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                    "chapter_flags, cover_last_modified, date_added) " +
                    "VALUES ($id, $source, 'm$id', 'title', 0, 1, 0, 0, 0, 0, 0)",
            )
            (1..chapters).forEach { number ->
                val scanlator = if (number <= hidden) "'hidden'" else "NULL"
                exec(
                    "INSERT INTO chapters(manga_id, url, name, scanlator, read, bookmark, last_page_read, " +
                        "chapter_number, source_order, date_fetch, date_upload) " +
                        "VALUES ($id, '/$id/$number', 'Chapter $number', $scanlator, 0, 0, 0, $number, " +
                        "$number, 0, 0)",
                )
            }
            if (hidden > 0) exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES ($id, 'hidden')")
        }

        override suspend fun libraryPrimary(preferred: List<Long>): Long {
            val items = MangaRepositoryImpl(database).getLibraryManga().map { row ->
                LibraryItem(
                    libraryManga = row,
                    downloadCount = 0,
                    unreadCount = 0,
                    isLocal = false,
                    badges = LibraryItem.Badges(
                        downloadCount = 0,
                        unreadCount = 0,
                        isLocal = false,
                        sourceLanguage = "",
                    ),
                )
            }
            return MangaMergeCollapse.collapse(
                items = items,
                membership = groups.getAllMemberships(ContentType.MANGA),
                mergingEnabled = true,
                showMergeSourceIcons = false,
                resolveSource = { error("source icons are off") },
                preferredSourceIds = preferred,
                recognizedChapterCounts = MergedChapterUnitRepositoryImpl(database).getRecognizedChapterCounts(),
            ).single().libraryManga.manga.id
        }

        // The stitcher loads chapters without the scanlator filter; see MangaGroupStitcher.
        override suspend fun stitchTrunk(preferred: List<Long>): Long {
            val chapters = ChapterRepositoryImpl(database)
            return ChapterAggregation.rankedMemberIds(
                chaptersBySource = members.keys.associateWith { chapters.getChapterByMangaId(it, false) },
                sourceIdByManga = members,
                preferredSourceIds = preferred,
            ).first()
        }
    }

    private inner class NovelSide : Side {
        private val members = mutableMapOf<Long, String>()

        override suspend fun entry(id: Long, source: Long, chapters: Int, hidden: Int) {
            members[id] = source.toString()
            exec(
                "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags) " +
                    "VALUES ($id, '$source', 'n$id', 'title', 0, 1, 0, 0)",
            )
            (1..chapters).forEach { number ->
                exec(
                    "INSERT INTO novel_chapters(novel_id, url, name, read, bookmark, chapter_number, " +
                        "source_order, date_fetch, date_upload) " +
                        "VALUES ($id, '/$id/$number', 'Chapter $number', 0, 0, $number, $number, 0, 0)",
                )
            }
        }

        override suspend fun libraryPrimary(preferred: List<Long>): Long =
            NovelMergeCollapse.collapse(
                library = NovelRepositoryImpl(database).getLibraryNovelAsFlow().first(),
                membership = groups.getAllMemberships(ContentType.NOVELS),
                mergingEnabled = true,
                preferredSourceIds = preferred.map(Long::toString),
            ).single().representative.novel.id

        override suspend fun stitchTrunk(preferred: List<Long>): Long {
            val chapters = NovelChapterRepositoryImpl(database)
            return NovelChapterAggregation.rankedMemberIds(
                chaptersByNovel = members.keys.associateWith { chapters.getByNovelId(it) },
                sourceIdByNovel = members,
                preferredSourceIds = preferred.map(Long::toString),
            ).first()
        }
    }

    private suspend fun exec(sql: String) {
        driver.execute(null, sql, 0).await()
    }

    private companion object {
        const val LARGER = 1L
        const val SMALLER = 2L
    }
}
