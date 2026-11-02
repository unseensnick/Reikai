package reikai.data.merge

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaGroupStitcher
import reikai.domain.manga.MangaMergeManager
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.MergedGroupStitcher
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.novel.NovelChapterAggregation
import reikai.domain.novel.NovelGroupStitcher
import reikai.domain.novel.NovelMergeManager
import reikai.presentation.library.LibraryBadgePrefs
import reikai.presentation.library.MangaMergeCollapse
import reikai.presentation.library.novels.NovelMergeCollapse
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.Novels
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.source.service.SourceManager

/**
 * The collapsed library row leads on the member the stitch makes the trunk, for both content types:
 * its cover, title and badge are that member's, and the details list opens on the stitch. Each type
 * ranks through the same source-priority kernel, then a chapter count, and the library has to read
 * the count the stitch ranks on. Runs the library's own rows and counts from real SQL, both before any
 * stitch is stored, which is how every group starts, and after a reconcile has saved the stitch's counts.
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
            database = DatabaseBindings.providesDatabase(driver)
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
        // display choice the stitch does not see. Novels offer no scanlator filter, so theirs are all shown.
        side.entry(id = LARGER, source = 100L, chapters = 10, hidden = 8)
        side.entry(id = SMALLER, source = 200L, chapters = 5)
        groups.createGroup(type, listOf(LARGER, SMALLER))

        side.libraryPrimary(preferred = emptyList()) shouldBe side.storedLead(preferred = emptyList())
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a preferred source leads the library as it leads the stitch`(type: ContentType) = runTest {
        val side = side(type)
        side.entry(id = LARGER, source = 100L, chapters = 10)
        side.entry(id = SMALLER, source = 200L, chapters = 5)
        groups.createGroup(type, listOf(LARGER, SMALLER))

        side.libraryPrimary(preferred = listOf(200L)) shouldBe side.storedLead(preferred = listOf(200L))
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a deletion that flips the chapter counts moves the library and the stored stitch together`(
        type: ContentType,
    ) = runTest {
        val side = side(type)
        side.entry(id = LARGER, source = 100L, chapters = 10)
        side.entry(id = SMALLER, source = 200L, chapters = 5)
        groups.createGroup(type, listOf(LARGER, SMALLER))
        side.storedLead(preferred = emptyList())

        exec("DELETE FROM ${side.chapterTable} WHERE ${side.ownerColumn} = $LARGER AND ${side.numberColumn} > 2")

        // The library reads what the stitch saved, so it moves once the reconcile after a write has run.
        val lead = side.storedLead(preferred = emptyList())
        side.libraryPrimary(preferred = emptyList()) shouldBe lead
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `after a reconcile the library leads on the stitch's trunk`(type: ContentType) = runTest {
        val side = side(type)
        // The smaller member lists each chapter twice, so a count of rows rather than chapters would
        // make it lead on manga, where the stitch collapses the second copy.
        side.entry(id = LARGER, source = 100L, chapters = 10)
        side.entry(id = SMALLER, source = 200L, chapters = 6, listedTwice = 6)
        groups.createGroup(type, listOf(LARGER, SMALLER))

        val lead = side.storedLead(preferred = emptyList())
        side.libraryPrimary(preferred = emptyList()) shouldBe lead
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a chapter write followed by a reconcile moves the library lead with the stitch`(type: ContentType) =
        runTest {
            val side = side(type)
            side.entry(id = LARGER, source = 100L, chapters = 10)
            side.entry(id = SMALLER, source = 200L, chapters = 5)
            groups.createGroup(type, listOf(LARGER, SMALLER))
            side.storedLead(preferred = emptyList())

            side.addChapters(id = SMALLER, numbers = 6..15)

            val lead = side.storedLead(preferred = emptyList())
            side.libraryPrimary(preferred = emptyList()) shouldBe lead
        }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `manage sources opens on the stitch's trunk`(type: ContentType) = runTest {
        val side = side(type)
        // As above: a scanlator the user hides is a display choice the ranking does not see.
        side.entry(id = LARGER, source = 100L, chapters = 10, hidden = 8)
        side.entry(id = SMALLER, source = 200L, chapters = 5)
        groups.createGroup(type, listOf(LARGER, SMALLER))

        side.managedLead(listOf(LARGER, SMALLER)) shouldBe side.storedLead(preferred = emptyList())
    }

    // The tests below are manga only: novels rank on the library row's own chapter count, which counts
    // rows as the novel stitch does, so the novel stitch saves no count to test.

    @Test
    fun `the stitch saves each member's count the way it ranks the trunk`() = runTest {
        val side = MangaSide()
        // Chapter 1 twice under two scanlators, and one chapter with no recognized number.
        side.entryWithNumbers(id = SMALLER, source = 100L, numbers = listOf(1.0, 1.0, 2.0, 3.0, -1.0))
        // 1.1 as a source reports it (a float widened to double) and as a parser reads it: one chapter.
        side.entryWithNumbers(id = LARGER, source = 200L, numbers = listOf(1.1f.toDouble(), 1.1, 2.0))
        groups.createGroup(ContentType.MANGA, listOf(LARGER, SMALLER))

        side.storedLead(preferred = emptyList())

        savedCounts() shouldBe mapOf(SMALLER to 3L, LARGER to 2L)
    }

    @Test
    fun `a chapter write followed by a reconcile updates the saved count`() = runTest {
        val side = MangaSide()
        side.entry(id = LARGER, source = 100L, chapters = 10)
        side.entry(id = SMALLER, source = 200L, chapters = 5)
        groups.createGroup(ContentType.MANGA, listOf(LARGER, SMALLER))
        side.storedLead(preferred = emptyList())

        side.addChapters(id = SMALLER, numbers = 6..15)
        side.storedLead(preferred = emptyList())

        savedCounts() shouldBe mapOf(LARGER to 10L, SMALLER to 15L)
    }

    @Test
    fun `a member leaving the library takes its saved count with it at the next reconcile`() = runTest {
        val side = MangaSide()
        side.entry(id = LARGER, source = 100L, chapters = 10)
        side.entry(id = SMALLER, source = 200L, chapters = 5)
        side.entry(id = LEAVING, source = 300L, chapters = 3)
        groups.createGroup(ContentType.MANGA, listOf(LARGER, SMALLER, LEAVING))
        side.storedLead(preferred = emptyList())

        exec("UPDATE manga SET user_favorite_at = NULL WHERE id = $LEAVING")
        side.storedLead(preferred = emptyList())

        savedCounts() shouldBe mapOf(LARGER to 10L, SMALLER to 5L)
    }

    @Test
    fun `a group not stitched yet ranks exactly beside one that is`() = runTest {
        val side = MangaSide()
        side.entry(id = LARGER, source = 100L, chapters = 10)
        side.entry(id = SMALLER, source = 200L, chapters = 5)
        groups.createGroup(ContentType.MANGA, listOf(LARGER, SMALLER))
        side.storedLead(preferred = emptyList())
        side.entry(id = UNSTITCHED_LARGER, source = 100L, chapters = 10)
        side.entry(id = UNSTITCHED_SMALLER, source = 200L, chapters = 5)
        groups.createGroup(ContentType.MANGA, listOf(UNSTITCHED_LARGER, UNSTITCHED_SMALLER))

        side.libraryLeads(preferred = emptyList()) shouldBe setOf(LARGER, UNSTITCHED_LARGER)
    }

    private fun savedCounts(): Map<Long, Long> =
        driver.executeQuery(
            null,
            "SELECT manga_id, recognized_count FROM merged_member_count",
            { cursor ->
                val rows = mutableMapOf<Long, Long>()
                while (cursor.next().value) rows[cursor.getLong(0)!!] = cursor.getLong(1)!!
                QueryResult.Value(rows)
            },
            0,
        ).value

    private fun side(type: ContentType): Side = if (type == ContentType.NOVELS) NovelSide() else MangaSide()

    private interface Side {
        val chapterTable: String

        val ownerColumn: String

        val numberColumn: String

        /** [listedTwice] of the chapters get a second row under the same number, as a second scanlator's. */
        suspend fun entry(id: Long, source: Long, chapters: Int, hidden: Int = 0, listedTwice: Int = 0)

        suspend fun addChapters(id: Long, numbers: IntRange)

        suspend fun libraryPrimary(preferred: List<Long>): Long

        /** The member the manage-sources dialog puts on top. */
        suspend fun managedLead(members: List<Long>): Long

        /** Reconciles the stored stitch through the real stitcher, then names the member whose copy it
         *  shows first. */
        suspend fun storedLead(preferred: List<Long>): Long
    }

    private suspend fun storedLead(stitcher: MergedGroupStitcher, ownerOf: suspend (Long) -> Long): Long {
        val units = MergedChapterUnitRepositoryImpl(database)
        ReconcileMergedChapters(units, setOf(stitcher)).await()
        val group = groups.getAllMemberships(stitcher.contentType).values.distinct().single()
        return ownerOf(
            units.getStitch(stitcher.contentType, group).first {
                it.unit == 0 && it.copyOrder == 0
            }.chapterId,
        )
    }

    private val preferences = ReikaiLibraryPreferences(InMemoryPreferenceStore())

    /** The library preferences with [preferred] first in a source ranking stored under [key]. */
    private fun preferring(key: String, preferred: List<Any>) = ReikaiLibraryPreferences(
        InMemoryPreferenceStore(sequenceOf(InMemoryPreference(key, preferred, emptyList<Any>()))),
    )

    private inner class MangaSide : Side {
        override val chapterTable = "chapter"

        override val ownerColumn = "manga_id"

        override val numberColumn = "remote_chapter_number"

        override suspend fun storedLead(preferred: List<Long>): Long {
            val chapters = ChapterRepositoryImpl(database)
            val sourceManager = mockk<SourceManager> { coEvery { get(any<Long>()) } returns null }
            val stitcher = MangaGroupStitcher(
                groups,
                GetMangaWithChapters(MangaRepositoryImpl(database), chapters),
                MangaMergeManager(groups, preferences, onMerged = {}) {},
                sourceManager,
                preferring("preferred_manga_sources", preferred),
            )
            return storedLead(stitcher) { chapters.getChapterById(it)!!.mangaId }
        }

        override suspend fun entry(id: Long, source: Long, chapters: Int, hidden: Int, listedTwice: Int) {
            manga(id, source)
            (1..chapters).forEach { number ->
                chapter(id, number.toDouble(), url = "/$id/$number", scanlator = "hidden".takeIf { number <= hidden })
                if (number <= listedTwice) chapter(id, number.toDouble(), url = "/$id/$number/b", scanlator = "b")
            }
            if (hidden > 0) exec("INSERT INTO excluded_scanlator(manga_id, scanlator) VALUES ($id, 'hidden')")
        }

        override suspend fun addChapters(id: Long, numbers: IntRange) {
            numbers.forEach { chapter(id, it.toDouble(), url = "/$id/$it", scanlator = null) }
        }

        suspend fun entryWithNumbers(id: Long, source: Long, numbers: List<Double>) {
            manga(id, source)
            numbers.forEachIndexed { index, number -> chapter(id, number, url = "/$id/$index", scanlator = "s$index") }
        }

        private suspend fun manga(id: Long, source: Long) {
            exec(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, " +
                    "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES ($id, $source, " +
                    "'m$id', 'title', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
            )
        }

        private suspend fun chapter(id: Long, number: Double, url: String, scanlator: String?) {
            exec(
                "INSERT INTO chapter(manga_id, remote_url, remote_name, remote_scanlator, " +
                    "user_read, user_bookmark, user_last_page_read, remote_chapter_number, " +
                    "remote_order, state_date_fetch, remote_date_upload, remote_memo) VALUES ($id, " +
                    "'$url', 'Chapter $number', ${scanlator?.let { "'$it'" } ?: "NULL"}, 0, 0, 0, $number, " +
                    "${number.toLong()}, 0, 0, '{}')",
            )
        }

        override suspend fun libraryPrimary(preferred: List<Long>): Long = libraryLeads(preferred).single()

        override suspend fun managedLead(members: List<Long>): Long = MergedChapterProvider(
            GetMangaWithChapters(MangaRepositoryImpl(database), ChapterRepositoryImpl(database)),
            mockk(),
            mockk(),
            preferences,
            mockk(),
        ).rankedMemberIds(members, emptyList()).first()

        /** The member each collapsed library row leads on. */
        suspend fun libraryLeads(preferred: List<Long>): Set<Long> {
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
                badgePrefs = LibraryBadgePrefs(false, false, false, false, false),
                membership = groups.getAllMemberships(ContentType.MANGA),
                mergingEnabled = true,
                showMergeSourceIcons = false,
                resolveSource = { error("source icons are off") },
                preferredSourceIds = preferred,
                recognizedChapterCounts = MergedChapterUnitRepositoryImpl(database)
                    .getRecognizedChapterCountsAsFlow().first(),
            ).mapTo(HashSet()) { it.libraryManga.manga.id }
        }
    }

    private inner class NovelSide : Side {
        override val chapterTable = "novel_chapters"

        override val ownerColumn = "novel_id"

        override val numberColumn = "chapter_number"

        override suspend fun storedLead(preferred: List<Long>): Long {
            val chapters = NovelChapterRepositoryImpl(database)
            val stitcher = NovelGroupStitcher(
                groups,
                NovelRepositoryImpl(database),
                chapters,
                NovelMergeManager(groups, preferences, onMerged = {}) {},
                preferring("preferred_novel_sources", preferred.map(Long::toString)),
            )
            return storedLead(stitcher) { chapters.getById(it)!!.novelId }
        }

        override suspend fun entry(id: Long, source: Long, chapters: Int, hidden: Int, listedTwice: Int) {
            exec(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                    "favorite_at) VALUES ($id, '$source', 'n$id', 'title', 0, 0, 0, 0)",
            )
            addChapters(id, 1..chapters)
            (1..listedTwice).forEach { chapter(id, it, url = "/$id/$it/b") }
        }

        override suspend fun addChapters(id: Long, numbers: IntRange) {
            numbers.forEach { chapter(id, it, url = "/$id/$it") }
        }

        private suspend fun chapter(id: Long, number: Int, url: String) {
            exec(
                "INSERT INTO novel_chapters(novel_id, url, name, read, bookmark, chapter_number, " +
                    "source_order, date_fetch, date_upload) " +
                    "VALUES ($id, '$url', 'Chapter $number', 0, 0, $number, $number, 0, 0)",
            )
        }

        override suspend fun managedLead(members: List<Long>): Long {
            val chapters = NovelChapterRepositoryImpl(database)
            return NovelChapterAggregation.ranking.rank(members.associateWith { chapters.getByNovelId(it) })
                .first().memberId
        }

        override suspend fun libraryPrimary(preferred: List<Long>): Long =
            NovelMergeCollapse.collapse(
                library = NovelRepositoryImpl(database).getLibraryNovelAsFlow().first(),
                membership = groups.getAllMemberships(ContentType.NOVELS),
                mergingEnabled = true,
                preferredSourceIds = preferred.map(Long::toString),
            ).single().representative.novel.id
    }

    private suspend fun exec(sql: String) {
        driver.execute(null, sql, 0).await()
    }

    private companion object {
        // The larger member has the higher id, so the id tiebreak alone would pick the wrong one.
        const val LARGER = 2L
        const val SMALLER = 1L
        const val UNSTITCHED_LARGER = 4L
        const val UNSTITCHED_SMALLER = 3L
        const val LEAVING = 5L
    }
}
