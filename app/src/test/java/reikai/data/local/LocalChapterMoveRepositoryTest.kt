package reikai.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.local.ChapterMove
import reikai.domain.local.OwnerMoves
import reikai.domain.local.SeriesAddress
import reikai.domain.local.SeriesRename
import reikai.domain.local.VolumeRowMove
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/** A chapter moved to another local series keeps its read state and what is keyed by its address, both types. */
class LocalChapterMoveRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
    }

    @AfterEach
    fun tearDown() = driver.close()

    private suspend fun exec(sql: String) = driver.execute(null, sql, 0).await()

    private suspend fun one(sql: String): String = driver.executeQuery(
        null,
        sql,
        { cursor -> QueryResult.Value(cursor.next().value.let { cursor.getString(0) }) },
        0,
    ).await().orEmpty()

    private suspend fun rows(sql: String): List<String> = driver.executeQuery(
        null,
        sql,
        { cursor ->
            QueryResult.Value(
                buildList { while (cursor.next().value) add(cursor.getString(0).orEmpty()) },
            )
        },
        0,
    ).await()

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the chapter moves to the other series, still read, with its number correction`(type: Type) = runTest {
        type.seed.forEach { exec(it) }

        LocalChapterMoveRepositoryImpl(database).move(type.contentType, null, listOf(MOVE), emptyList())

        listOf(one(type.chapterQuery), one(type.overrideQuery)) shouldBe listOf("1|A/B/c1|1", "1|A/B/c1")
    }

    @Test
    fun `a moved manga chapter's history follows it`() = runTest {
        Type.MANGA.seed.forEach { exec(it) }
        exec("INSERT INTO history(manga_id, chapter_id, read_at, read_duration) VALUES (2, 10, 0, 0)")

        LocalChapterMoveRepositoryImpl(database).move(ContentType.MANGA, null, listOf(MOVE), emptyList())

        one("SELECT manga_id FROM history WHERE chapter_id = 10") shouldBe "1"
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a renamed series takes its new address, its chapters', and its volumes' names and picked chip`(type: Type) =
        runTest {
            type.seed.forEach { exec(it) }
            val dbType = if (type.contentType == ContentType.NOVELS) 1 else 0
            exec(
                "INSERT INTO local_volume(content_type, series, volume_key, position, label) VALUES ($dbType, 'B', 'v1', 0, 'One')",
            )
            exec("INSERT INTO local_volume_pick(content_type, entry_id, volume_key) VALUES ($dbType, 2, 'v1')")

            LocalChapterMoveRepositoryImpl(database).rename(
                type.contentType,
                SeriesRename(2, "B", "Bee", mapOf("v1" to "Vol. 1")),
                listOf(ChapterMove("B/c1", "Bee/c1")),
            )

            listOf(
                one(type.seriesQuery),
                one(type.chapterQuery),
                one("SELECT series || '|' || volume_key || '|' || label FROM local_volume"),
                one("SELECT volume_key FROM local_volume_pick"),
            ) shouldBe listOf("Bee", "2|Bee/c1|1", "Bee|Vol. 1|One", "Vol. 1")
        }

    @Test
    fun `a moved volume takes its saved order and name to its new series and key`() = runTest {
        exec(
            "INSERT INTO local_volume(content_type, series, volume_key, position, label) VALUES (0, 'B', 'v1', 0, 'One')",
        )

        LocalChapterMoveRepositoryImpl(database).move(
            ContentType.MANGA,
            null,
            emptyList(),
            listOf(VolumeRowMove("B", "v1", "A", "v1 (2)", 3)),
        )

        one("SELECT series || '|' || volume_key || '|' || position || '|' || label FROM local_volume") shouldBe
            "A|v1 (2)|3|One"
    }

    @Test
    fun `a volume moving onto the saved row of a deleted one replaces it`() = runTest {
        exec(
            "INSERT INTO local_volume(content_type, series, volume_key, position, label) VALUES (0, 'A', 'v1', 9, 'Old')",
        )
        exec(
            "INSERT INTO local_volume(content_type, series, volume_key, position, label) VALUES (0, 'B', 'v1', 0, 'One')",
        )

        LocalChapterMoveRepositoryImpl(database).move(
            ContentType.MANGA,
            null,
            emptyList(),
            listOf(VolumeRowMove("B", "v1", "A", "v1", 1)),
        )

        rows("SELECT series || '|' || volume_key || '|' || position || '|' || label FROM local_volume") shouldBe
            listOf("A|v1|1|One")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a move that fails part way writes nothing`(type: Type) = runTest {
        type.seed.forEach { exec(it) }
        // A chapter already at the address the move takes, which the owner's unique address refuses.
        exec(type.seed.first { "'B/c1', 'C'" in it }.replace("(10, 2, 'B/c1'", "(11, 1, 'A/B/c1'"))

        runCatching {
            LocalChapterMoveRepositoryImpl(database).move(
                type.contentType,
                SeriesAddress(2, "Bee"),
                listOf(MOVE),
                emptyList(),
            )
        }

        listOf(one(type.seriesQuery), one(type.chapterQuery)) shouldBe listOf("B", "2|B/c1|1")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a rename onto the saved rows of deleted files replaces them`(type: Type) = runTest {
        type.seed.forEach { exec(it) }
        val dbType = if (type.contentType == ContentType.NOVELS) 1 else 0
        listOf("('B', 'v1', 0, 'One')", "('B', 'Vol. 1', 9, 'Old')", "('Bee', 'v9', 9, 'Old')").forEach {
            exec(
                "INSERT INTO local_volume(content_type, series, volume_key, position, label) VALUES ($dbType, ${it.drop(
                    1,
                )}",
            )
        }

        LocalChapterMoveRepositoryImpl(database).rename(
            type.contentType,
            SeriesRename(2, "B", "Bee", mapOf("v1" to "Vol. 1")),
            emptyList(),
        )

        rows("SELECT series || '|' || volume_key || '|' || label FROM local_volume") shouldBe listOf("Bee|Vol. 1|One")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `deleted volumes forget their saved order and the chip left on them`(type: Type) = runTest {
        val dbType = if (type.contentType == ContentType.NOVELS) 1 else 0
        exec("INSERT INTO local_volume(content_type, series, volume_key, position) VALUES ($dbType, 'B', 'v1', 0)")
        exec("INSERT INTO local_volume(content_type, series, volume_key, position) VALUES ($dbType, 'B', 'v2', 1)")
        exec("INSERT INTO local_volume_pick(content_type, entry_id, volume_key) VALUES ($dbType, 2, 'v1')")

        LocalChapterMoveRepositoryImpl(database).forgetVolumes(type.contentType, 2, "B", listOf("v1"))

        rows("SELECT volume_key FROM local_volume") + rows("SELECT volume_key FROM local_volume_pick") shouldBe
            listOf("v2")
    }

    private companion object {
        val MOVE = OwnerMoves(2, 1, listOf(ChapterMove("B/c1", "A/B/c1")))
    }

    enum class Type(
        val contentType: ContentType,
        val seed: List<String>,
        val chapterQuery: String,
        val overrideQuery: String,
        val seriesQuery: String,
    ) {
        MANGA(
            ContentType.MANGA,
            listOf(1L to "A", 2L to "B").map { (id, url) ->
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                    "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                    "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "($id, 0, '$url', '$url', 0, 1, 0, 0, 0, 1, 0, 0, '', '{}')"
            } + listOf(
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                    "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                    "remote_date_upload, remote_memo) VALUES (10, 2, 'B/c1', 'C', NULL, 1, 0, 0, 1.0, 0, 0, 0, '{}')",
                "INSERT INTO manga_chapter_number_override(manga_id, url, number, source_number) VALUES (2, 'B/c1', 5.0, 1.0)",
            ),
            "SELECT manga_id || '|' || remote_url || '|' || user_read FROM chapter WHERE id = 10",
            "SELECT manga_id || '|' || url FROM manga_chapter_number_override",
            "SELECT remote_url FROM manga WHERE id = 2",
        ),
        NOVEL(
            ContentType.NOVELS,
            listOf(1L to "A", 2L to "B").map { (id, url) ->
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES ($id, 'local', '$url', '$url', 0, 0, 0, 0)"
            } + listOf(
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES (10, 2, 'B/c1', 'C', 1, 0, 0, 1.0, 0, 0, 0)",
                "INSERT INTO novel_chapter_number_override(novel_id, url, number, source_number) VALUES (2, 'B/c1', 5.0, 1.0)",
            ),
            "SELECT novel_id || '|' || url || '|' || read FROM novel_chapters WHERE _id = 10",
            "SELECT novel_id || '|' || url FROM novel_chapter_number_override",
            "SELECT url FROM novels WHERE _id = 2",
        ),
    }
}
