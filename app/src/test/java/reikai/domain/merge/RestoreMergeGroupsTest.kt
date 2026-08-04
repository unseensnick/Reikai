package reikai.domain.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.merge.MergeGroupRepositoryImpl
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
 * Restoring backed-up merge groups onto a library that already has grouping of its own. Runs against
 * the real schema, because the defect this covers was an interaction between successive writes.
 */
class RestoreMergeGroupsTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var repository: MergeGroupRepositoryImpl
    private lateinit var restore: RestoreMergeGroups

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
            repository = MergeGroupRepositoryImpl(database)
            restore = RestoreMergeGroups(repository)
        }
    }

    @AfterEach
    fun tearDown() = driver.close()

    private suspend fun insertManga(id: Long) {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                "chapter_flags, cover_last_modified, date_added) " +
                "VALUES ($id, 1, 'm-url-$id', 'title', 0, 0, 0, 0, 0, 0, 0)",
            0,
        ).await()
    }

    private suspend fun groupOf(id: Long): List<Long> {
        val groupId = repository.getGroupId(ContentType.MANGA, id) ?: return emptyList()
        return repository.getMembers(ContentType.MANGA, groupId)
    }

    @Test
    fun `two separate backup groups stay separate when a local group bridges them`() = runTest {
        (1L..4L).forEach { insertManga(it) }
        // The library has A merged with C, which the backup knows nothing about.
        repository.createGroup(ContentType.MANGA, listOf(1, 3))

        restore(ContentType.MANGA, listOf(listOf(1, 2), listOf(3, 4)))

        // Restoring one group at a time fed each write the previous one's result, so the second group
        // absorbed the first through the local bridge and the user got one four-member group whose
        // card interleaved four sources.
        groupOf(1) shouldContainExactly listOf(1L, 2L)
        groupOf(3) shouldContainExactly listOf(3L, 4L)
    }

    @Test
    fun `the result does not depend on the order the backup lists its groups`() = runTest {
        (1L..4L).forEach { insertManga(it) }
        repository.createGroup(ContentType.MANGA, listOf(1, 3))

        restore(ContentType.MANGA, listOf(listOf(3, 4), listOf(1, 2)))

        groupOf(1) shouldContainExactly listOf(1L, 2L)
        groupOf(3) shouldContainExactly listOf(3L, 4L)
    }

    @Test
    fun `local members the backup says nothing about keep their own group and its ranking`() = runTest {
        (1L..4L).forEach { insertManga(it) }
        // Locally A, C and D are one hand-ordered group; the backup only describes A.
        val local = repository.createGroup(ContentType.MANGA, listOf(1, 3, 4))!!
        repository.setSourceOrder(ContentType.MANGA, local, listOf(4, 3, 1))

        restore(ContentType.MANGA, listOf(listOf(1, 2)))

        groupOf(1) shouldContainExactly listOf(1L, 2L)
        // C and D are not part of the restore, so their grouping survives it, order and all.
        groupOf(3) shouldContainExactly listOf(4L, 3L)
        val remainder = repository.getGroupId(ContentType.MANGA, 3)!!
        repository.getGroup(remainder)!!.overrideSourceRanking shouldBe true
    }

    @Test
    fun `a local group left with one member is dissolved rather than kept as a group of one`() = runTest {
        (1L..3L).forEach { insertManga(it) }
        repository.createGroup(ContentType.MANGA, listOf(1, 3))

        restore(ContentType.MANGA, listOf(listOf(1, 2)))

        groupOf(1) shouldContainExactly listOf(1L, 2L)
        repository.getGroupId(ContentType.MANGA, 3).shouldBeNull()
    }

    @Test
    fun `an entry named by two backup groups lands in the first`() = runTest {
        (1L..4L).forEach { insertManga(it) }

        restore(ContentType.MANGA, listOf(listOf(1, 2), listOf(2, 3, 4)))

        groupOf(1) shouldContainExactly listOf(1L, 2L)
        // The second group keeps the members it can still claim rather than being dropped whole.
        groupOf(3) shouldContainExactly listOf(3L, 4L)
    }

    @Test
    fun `a group with fewer than two resolvable members is not created`() = runTest {
        insertManga(1)

        restore(ContentType.MANGA, listOf(listOf(1)))

        repository.getGroupId(ContentType.MANGA, 1).shouldBeNull()
    }
}
