package reikai.data.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

/**
 * Phase 0 of the merge-system rebuild: proves the persisted merge-group storage round-trips and that
 * the FK cascade removes membership when a parent entry is deleted (the fix for the pref-era id-reuse
 * capture). Establishes the reusable pattern for testing this schema: a pure-JVM in-memory SQLite DB
 * built from [Database.Schema] with foreign keys enabled, so later phases' schema work can reuse it.
 */
class MergeGroupRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var repository: MergeGroupRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            // Cascade only fires with foreign keys on; the app enables the same pragma in AppModule.
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
            repository = MergeGroupRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `createGroup persists membership and reads it back in order`() = runTest {
        insertManga(1)
        insertManga(2)

        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        repository.getMembers(ContentType.MANGA, groupId) shouldBe listOf(1L, 2L)
        repository.getGroupId(ContentType.MANGA, 1) shouldBe groupId
        repository.getGroupId(ContentType.MANGA, 2) shouldBe groupId
        val group = repository.getGroup(groupId)!!
        group.contentType shouldBe ContentType.MANGA
        group.overrideSourceRanking shouldBe false
    }

    @Test
    fun `createGroup returns null for fewer than two distinct ids`() = runTest {
        insertManga(1)

        repository.createGroup(ContentType.MANGA, listOf(1)) shouldBe null
        repository.createGroup(ContentType.MANGA, emptyList()) shouldBe null
        repository.createGroup(ContentType.MANGA, listOf(1, 1)) shouldBe null
    }

    @Test
    fun `deleting a manga cascades its membership away`() = runTest {
        insertManga(1)
        insertManga(2)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        driver.execute(null, "DELETE FROM mangas WHERE _id = 1", 0).await()

        repository.getGroupId(ContentType.MANGA, 1).shouldBeNull()
        repository.getMembers(ContentType.MANGA, groupId) shouldBe listOf(2L)
    }

    @Test
    fun `a manga cannot belong to two groups`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        // Manga 1 is already a member; UNIQUE(manga_id) rejects re-grouping it into another group.
        shouldThrow<Exception> { repository.createGroup(ContentType.MANGA, listOf(1, 3)) }
    }

    @Test
    fun `dissolveGroup removes the group and all members`() = runTest {
        insertManga(1)
        insertManga(2)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        repository.dissolveGroup(groupId)

        repository.getGroup(groupId).shouldBeNull()
        repository.getMembers(ContentType.MANGA, groupId) shouldBe emptyList()
        repository.getGroupId(ContentType.MANGA, 1).shouldBeNull()
    }

    @Test
    fun `novel groups persist and cascade like manga`() = runTest {
        insertNovel(1)
        insertNovel(2)

        val groupId = repository.createGroup(ContentType.NOVELS, listOf(1, 2))!!
        repository.getMembers(ContentType.NOVELS, groupId) shouldBe listOf(1L, 2L)
        repository.getGroup(groupId)!!.contentType shouldBe ContentType.NOVELS

        driver.execute(null, "DELETE FROM novels WHERE _id = 1", 0).await()

        repository.getGroupId(ContentType.NOVELS, 1).shouldBeNull()
        repository.getMembers(ContentType.NOVELS, groupId) shouldBe listOf(2L)
    }

    @Test
    fun `merge combines ungrouped entries into one group`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)

        val groupId = repository.merge(ContentType.MANGA, listOf(1, 2, 3))!!

        repository.getMembers(ContentType.MANGA, groupId) shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `merge absorbs the existing groups its ids already belong to`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        insertManga(4)
        val g1 = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        val g2 = repository.createGroup(ContentType.MANGA, listOf(3, 4))!!

        // Merging one member of each group pulls in every hidden member and dissolves the old groups.
        val merged = repository.merge(ContentType.MANGA, listOf(1, 3))!!

        repository.getMembers(ContentType.MANGA, merged) shouldBe listOf(1L, 2L, 3L, 4L)
        repository.getGroup(g1).shouldBeNull()
        repository.getGroup(g2).shouldBeNull()
    }

    @Test
    fun `merge returns null for fewer than two ids`() = runTest {
        insertManga(1)

        repository.merge(ContentType.MANGA, listOf(1)) shouldBe null
    }

    @Test
    fun `removeFromGroup keeps two or more survivors grouped`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2, 3))!!

        val survivors = repository.removeFromGroup(ContentType.MANGA, listOf(1))

        survivors shouldBe listOf(2L, 3L)
        repository.getGroupId(ContentType.MANGA, 1).shouldBeNull()
        repository.getGroupId(ContentType.MANGA, 2) shouldBe groupId
    }

    @Test
    fun `removeFromGroup dissolves the group when one survivor remains`() = runTest {
        insertManga(1)
        insertManga(2)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        val survivors = repository.removeFromGroup(ContentType.MANGA, listOf(1))

        survivors shouldBe listOf(2L)
        repository.getGroup(groupId).shouldBeNull()
        repository.getGroupId(ContentType.MANGA, 2).shouldBeNull()
    }

    @Test
    fun `dissolve ungroups every member`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        repository.createGroup(ContentType.MANGA, listOf(1, 2, 3))!!

        repository.dissolve(ContentType.MANGA, 2)

        repository.getGroupId(ContentType.MANGA, 1).shouldBeNull()
        repository.getGroupId(ContentType.MANGA, 3).shouldBeNull()
    }

    @Test
    fun `clearAll dissolves every group of that type only`() = runTest {
        insertManga(1)
        insertManga(2)
        insertNovel(1)
        insertNovel(2)
        repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        val novelGroup = repository.createGroup(ContentType.NOVELS, listOf(1, 2))!!

        repository.clearAll(ContentType.MANGA)

        repository.getGroupId(ContentType.MANGA, 1).shouldBeNull()
        repository.getGroupId(ContentType.NOVELS, 1) shouldBe novelGroup
    }

    @Test
    fun `getAllMemberships maps each member to its group`() = runTest {
        insertManga(1)
        insertManga(2)
        insertManga(3)
        val groupId = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        repository.getAllMemberships(ContentType.MANGA) shouldBe mapOf(1L to groupId, 2L to groupId)
    }

    // Minimal valid parent rows: only the NOT NULL columns without a default need values.
    private suspend fun insertManga(id: Long) {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                "chapter_flags, cover_last_modified, date_added) " +
                "VALUES ($id, 1, 'm-url-$id', 'title', 0, 0, 0, 0, 0, 0, 0)",
            0,
        ).await()
    }

    private suspend fun insertNovel(id: Long) {
        driver.execute(
            null,
            "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags) " +
                "VALUES ($id, 'src', 'n-url-$id', 'title', 0, 0, 0, 0)",
            0,
        ).await()
    }
}
