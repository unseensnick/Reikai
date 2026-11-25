package reikai.data.merge

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.library.ContentType
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import kotlin.random.Random

/**
 * The reconcile checks only the groups merged_group_pending lists, so after any write the list has to
 * hold every group the stale views would find. The views are the oracle: each case stitches every group
 * exactly, clears the list, makes one write and compares. Entries 1 to 3 form group 1 with entry 7, which
 * is out of the library; 4 and 5 form group 2; 6 is in no group. Each entry has three chapters.
 */
class MergedGroupPendingConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var units: MergedChapterUnitRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            units = MergedChapterUnitRepositoryImpl(DatabaseBindings.providesDatabase(driver))
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("writes")
    fun `every group a write leaves stale is pending`(type: ContentType, write: Write) = runTest {
        assumeTrue(write != Write.RENAME_CHAPTER || type == ContentType.NOVELS, "a manga's name is no stitch input")
        val t = Tables.of(type)
        seed(t)
        exec("DELETE FROM merged_group_pending")

        write.sql(t).forEach { exec(it) }

        val stale = longs("SELECT DISTINCT groupId FROM ${t.staleView}").toSet()
        (stale.isNotEmpty() to stale - pending()) shouldBe (true to emptySet())
    }

    @ParameterizedTest
    @EnumSource(names = ["MANGA", "NOVELS"])
    fun `random writes and reconciles never leave a stale group unlisted`(type: ContentType) = runTest {
        val t = Tables.of(type)
        seed(t)
        val random = Random(20261012)
        val misses = mutableListOf<String>()
        var nextId = 10_000L

        repeat(400) { step ->
            val entries = longs("SELECT ${t.entryId} FROM ${t.entryTable}")
            val chapters = longs("SELECT ${t.chapterId} FROM ${t.chapterTable}")
            val groups = longs("SELECT _id FROM merge_group WHERE content_type = ${t.contentType}")
            val op = random.nextInt(12)
            when {
                op == 0 && entries.isNotEmpty() -> exec(t.chapter(++nextId, entries.random(random), nextId.toDouble()))
                op == 1 && chapters.isNotEmpty() ->
                    exec("DELETE FROM ${t.chapterTable} WHERE ${t.chapterId} = ${chapters.random(random)}")
                op == 2 && chapters.isNotEmpty() ->
                    exec(
                        "UPDATE ${t.chapterTable} SET ${t.number} = ${random.nextInt(
                            9,
                        )} WHERE ${t.chapterId} = ${chapters.random(random)}",
                    )
                op == 3 && chapters.isNotEmpty() ->
                    exec(
                        "UPDATE ${t.chapterTable} SET ${t.order} = ${random.nextInt(
                            9,
                        )} WHERE ${t.chapterId} = ${chapters.random(random)}",
                    )
                op == 4 && chapters.isNotEmpty() && entries.isNotEmpty() ->
                    exec(
                        "UPDATE OR IGNORE ${t.chapterTable} SET ${t.owner} = ${entries.random(random)} " +
                            "WHERE ${t.chapterId} = ${chapters.random(random)}",
                    )
                op == 5 && chapters.isNotEmpty() ->
                    exec(
                        "UPDATE ${t.chapterTable} SET ${t.name} = 'n$step' WHERE ${t.chapterId} = ${chapters.random(
                            random,
                        )}",
                    )
                op == 6 && entries.isNotEmpty() && groups.isNotEmpty() ->
                    exec(
                        "INSERT OR IGNORE INTO ${t.memberTable}(group_id, ${t.owner}) " +
                            "VALUES (${groups.random(random)}, ${entries.random(random)})",
                    )
                op == 7 -> longs("SELECT ${t.owner} FROM ${t.memberTable}").takeIf { it.isNotEmpty() }?.let {
                    exec("DELETE FROM ${t.memberTable} WHERE ${t.owner} = ${it.random(random)}")
                }
                op == 8 && entries.isNotEmpty() -> exec(
                    "UPDATE ${t.entryTable} SET ${t.favorite} = CASE WHEN ${t.favorite} IS NULL THEN 1 ELSE NULL END " +
                        "WHERE ${t.entryId} = ${entries.random(random)}",
                )
                op == 9 && groups.isNotEmpty() && random.nextInt(4) == 0 ->
                    exec("DELETE FROM merge_group WHERE _id = ${groups.random(random)}")
                op == 10 -> exec("INSERT INTO merge_group(content_type) VALUES (${t.contentType})")
                else -> reconcile(t)
            }
            val stale = longs("SELECT DISTINCT groupId FROM ${t.staleView}").toSet()
            if (!pending().containsAll(stale)) misses += "step $step, op $op: ${stale - pending()}"
        }

        misses shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(names = ["MANGA", "NOVELS"])
    fun `a claim a failed reconcile left is claimed again`(type: ContentType) = runTest {
        seed(Tables.of(type))
        units.claimPendingGroups(type)

        units.claimPendingGroups(type).toSet() shouldBe setOf(1L, 2L)
    }

    @ParameterizedTest
    @EnumSource(names = ["MANGA", "NOVELS"])
    fun `a write landing while its group is claimed keeps the group listed`(type: ContentType) = runTest {
        val t = Tables.of(type)
        seed(t)
        units.claimPendingGroups(type)

        exec(t.chapter(150, 1, 9.0))
        units.releaseClaim(1L)

        units.claimPendingGroups(type).toSet() shouldBe setOf(1L, 2L)
    }

    @ParameterizedTest
    @EnumSource(names = ["MANGA", "NOVELS"])
    fun `a write landing on a claim a failed reconcile left is claimed again`(type: ContentType) = runTest {
        val t = Tables.of(type)
        seed(t)
        units.claimPendingGroups(type)

        exec(t.chapter(150, 1, 9.0))

        units.claimPendingGroups(type).toSet() shouldBe setOf(1L, 2L)
    }

    /** What a reconcile does, with an exact stitch standing in for the real one. */
    private suspend fun reconcile(t: Tables) {
        units.claimPendingGroups(t.type).forEach { group ->
            stitch(t, group)
            units.releaseClaim(group)
        }
    }

    private suspend fun seed(t: Tables) {
        (1L..7L).forEach { id ->
            exec(t.entry(id, favorite = id != 7L))
            (0..2).forEach { n -> exec(t.chapter(id * 100 + n, id, n.toDouble())) }
        }
        exec("INSERT INTO merge_group(_id, content_type) VALUES (1, ${t.contentType}), (2, ${t.contentType})")
        exec("INSERT INTO ${t.memberTable}(group_id, ${t.owner}) VALUES (1, 1), (1, 2), (1, 3), (1, 7), (2, 4), (2, 5)")
        listOf(1L, 2L).forEach { stitch(t, it) }
    }

    /** Every library member chapter of [group] as its own unit, stamped with its current inputs. */
    private suspend fun stitch(t: Tables, group: Long) {
        exec(
            "DELETE FROM ${t.unitTable} WHERE group_id = $group OR chapter_id IN (SELECT C.${t.chapterId} " +
                "FROM ${t.memberTable} MG JOIN ${t.chapterTable} C ON C.${t.owner} = MG.${t.owner} " +
                "WHERE MG.group_id = $group)",
        )
        exec(
            "INSERT INTO ${t.unitTable}(chapter_id, group_id, unit, copy_order, ${t.derived}) " +
                "SELECT C.${t.chapterId}, $group, C.${t.chapterId}, 0, ${t.derivedFrom} FROM ${t.chapterTable} C " +
                "JOIN ${t.memberTable} MG ON MG.${t.owner} = C.${t.owner} JOIN ${t.entryTable} E " +
                "ON E.${t.entryId} = C.${t.owner} AND E.${t.favorite} IS NOT NULL WHERE MG.group_id = $group",
        )
        exec(
            "INSERT OR REPLACE INTO merged_group_ranking(group_id, ranking, chapter_count) SELECT $group, 'r', " +
                "(SELECT count(*) FROM ${t.unitTable} WHERE group_id = $group) " +
                "WHERE EXISTS (SELECT 1 FROM merge_group WHERE _id = $group)",
        )
    }

    private suspend fun pending() = longs("SELECT group_id FROM merged_group_pending WHERE claimed = 0").toSet()

    private suspend fun exec(sql: String) {
        driver.execute(null, sql, 0).await()
    }

    private suspend fun longs(sql: String): List<Long> = driver.executeQuery(
        null,
        sql,
        { cursor -> QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getLong(0)!!) }) },
        0,
    ).await()

    /** One write per stitch input the stale views compare, against the seeded rows. */
    enum class Write(val sql: (Tables) -> List<String>) {
        INSERT_CHAPTER({ listOf(it.chapter(150, 1, 9.0)) }),
        DELETE_CHAPTER({ listOf("DELETE FROM ${it.chapterTable} WHERE ${it.chapterId} = 101") }),
        RENUMBER_CHAPTER({ listOf("UPDATE ${it.chapterTable} SET ${it.number} = 9 WHERE ${it.chapterId} = 101") }),
        REORDER_CHAPTER({ listOf("UPDATE ${it.chapterTable} SET ${it.order} = 9 WHERE ${it.chapterId} = 101") }),
        RENAME_CHAPTER({ listOf("UPDATE ${it.chapterTable} SET ${it.name} = 'other' WHERE ${it.chapterId} = 101") }),
        MOVE_CHAPTER({ listOf("UPDATE ${it.chapterTable} SET ${it.owner} = 4 WHERE ${it.chapterId} = 101") }),
        JOIN_GROUP({ listOf("INSERT INTO ${it.memberTable}(group_id, ${it.owner}) VALUES (2, 6)") }),
        LEAVE_GROUP({ listOf("DELETE FROM ${it.memberTable} WHERE ${it.owner} = 3") }),
        SWITCH_GROUP({ listOf("UPDATE ${it.memberTable} SET group_id = 2 WHERE ${it.owner} = 3") }),
        LEAVE_LIBRARY({ listOf("UPDATE ${it.entryTable} SET ${it.favorite} = NULL WHERE ${it.entryId} = 2") }),
        JOIN_LIBRARY({ listOf("UPDATE ${it.entryTable} SET ${it.favorite} = 1 WHERE ${it.entryId} = 7") }),
        DELETE_ENTRY({ listOf("DELETE FROM ${it.entryTable} WHERE ${it.entryId} = 2") }),
    }

    /** Each type's table and column names, and the rows the cases write. */
    class Tables private constructor(
        val type: ContentType,
        val contentType: Int,
        val entryTable: String,
        val entryId: String,
        val favorite: String,
        val chapterTable: String,
        val chapterId: String,
        val owner: String,
        val number: String,
        val order: String,
        val name: String,
        val memberTable: String,
        val unitTable: String,
        val derived: String,
        val derivedFrom: String,
        val staleView: String,
    ) {
        fun entry(id: Long, favorite: Boolean): String {
            val at = if (favorite) "1" else "NULL"
            return if (type == ContentType.NOVELS) {
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES ($id, 'src', 'n$id', 'title $id', 0, 0, 0, $at)"
            } else {
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                    "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                    "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "($id, 1, 'm$id', 'title $id', 0, 0, 0, 0, 0, $at, 0, 0, '', '{}')"
            }
        }

        fun chapter(id: Long, owner: Long, number: Double): String = if (type == ContentType.NOVELS) {
            "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                "($id, $owner, 'u$id', 'c$id', 0, 0, 0, $number, 0, 0, 0)"
        } else {
            "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                "remote_date_upload, remote_memo) VALUES ($id, $owner, 'u$id', 'c$id', NULL, 0, 0, 0, " +
                "$number, 0, 0, 0, '{}')"
        }

        override fun toString() = type.name

        companion object {
            fun of(type: ContentType) = if (type == ContentType.NOVELS) {
                Tables(
                    type, 1, "novels", "_id", "favorite_at", "novel_chapters", "_id", "novel_id",
                    "chapter_number", "source_order", "name", "merge_group_novel", "merged_novel_chapter_unit",
                    "derived_name, derived_number, derived_order", "C.name, C.chapter_number, C.source_order",
                    "mergedNovelChapterStaleView",
                )
            } else {
                Tables(
                    type, 0, "manga", "id", "user_favorite_at", "chapter", "id", "manga_id",
                    "remote_chapter_number", "remote_order", "remote_name", "merge_group_manga",
                    "merged_chapter_unit", "derived_number, derived_order", "C.remote_chapter_number, C.remote_order",
                    "mergedChapterStaleView",
                )
            }
        }
    }

    companion object {
        @JvmStatic
        fun writes() = listOf(ContentType.MANGA, ContentType.NOVELS).flatMap { type ->
            Write.entries.map { arrayOf(type, it) }
        }
    }
}
