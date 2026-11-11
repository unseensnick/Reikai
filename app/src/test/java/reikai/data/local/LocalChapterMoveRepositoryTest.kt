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
import reikai.data.chapter.ChapterTombstoneRepositoryImpl
import reikai.domain.library.ContentType
import reikai.domain.local.LocalFileJournalPlan
import reikai.domain.local.SeriesRename
import reikai.domain.local.VolumeOwner
import reikai.domain.local.VolumeRowMove
import reikai.source.local.localChapterUnit
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

        regroup(type.contentType)

        listOf(one(type.chapterQuery), one(type.overrideQuery)) shouldBe listOf("1|B/c1|1", "1|B/c1")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a regroup moves every chapter of the volumes it names and none of another folder`(type: Type) = runTest {
        type.seed.forEach { exec(it) }
        exec(type.seed.first { "'B/c1', 'C'" in it }.replace("(10, 2, 'B/c1'", "(11, 2, 'C/c2'"))
        exec(type.tombstone(2, "B/c3"))

        regroup(type.contentType)

        rows(type.ownerAndUrl) shouldBe listOf("1|B/c1", "1|B/c3", "2|C/c2")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter goes to the series the table now names for its volume, and one held all along stays`(type: Type) =
        runTest {
            type.seed.forEach { exec(it) }
            // A holds B's volume v and a loose chapter of B; the table gives B's folder whole to C but v to A.
            exec(type.seed.first { "'B/c1', 'C'" in it }.replace("(10, 2, 'B/c1'", "(11, 1, 'B/v/c2'"))
            exec(type.seed.first { "'B/c1', 'C'" in it }.replace("(10, 2, 'B/c1'", "(12, 1, 'B/c3'"))

            repository().regroup(
                type.contentType,
                mapOf("A" to 1L, "B" to 2L, "C" to 3L),
                emptyList(),
                listOf(VolumeOwner("B", VolumeOwner.WHOLE, "C"), VolumeOwner("B", "v", "A")),
                emptyList(),
                ::localChapterUnit,
            )

            rows(type.ownerAndUrl) shouldBe listOf("1|B/v/c2", "3|B/c1", "3|B/c3")
        }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a regroup writes which series lists the volumes in its own transaction`(type: Type) = runTest {
        type.seed.forEach { exec(it) }
        val dbType = if (type.contentType == ContentType.NOVELS) 1 else 0
        exec("INSERT INTO local_volume_owner(content_type, folder, volume_key, owner) VALUES ($dbType, 'X', '', 'B')")

        repository().regroup(
            type.contentType,
            SERIES,
            remove = listOf(VolumeOwner("X", "", "B")),
            add = listOf(VolumeOwner("B", VolumeOwner.WHOLE, "A")),
            volumes = emptyList(),
            unitOf = ::localChapterUnit,
        )

        rows("SELECT folder || '|' || volume_key || '|' || owner FROM local_volume_owner") shouldBe listOf("B|/|A")
    }

    @Test
    fun `a moved manga chapter's history follows it`() = runTest {
        Type.MANGA.seed.forEach { exec(it) }
        exec("INSERT INTO history(manga_id, chapter_id, read_at, read_duration) VALUES (2, 10, 0, 0)")

        regroup(ContentType.MANGA)

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

            repository().rename(type.contentType, SeriesRename(2, "B", "Bee", mapOf("v1" to "Vol. 1")), null, ::toBee)

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

        moveVolumes(VolumeRowMove("B", "v1", "A", "B/v1", 3))

        one("SELECT series || '|' || volume_key || '|' || position || '|' || label FROM local_volume") shouldBe
            "A|B/v1|3|One"
    }

    @Test
    fun `a volume moving onto the saved row of a deleted one replaces it`() = runTest {
        exec(
            "INSERT INTO local_volume(content_type, series, volume_key, position, label) VALUES (0, 'A', 'v1', 9, 'Old')",
        )
        exec(
            "INSERT INTO local_volume(content_type, series, volume_key, position, label) VALUES (0, 'B', 'v1', 0, 'One')",
        )

        moveVolumes(VolumeRowMove("B", "v1", "A", "v1", 1))

        rows("SELECT series || '|' || volume_key || '|' || position || '|' || label FROM local_volume") shouldBe
            listOf("A|v1|1|One")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a regroup that fails part way writes nothing`(type: Type) = runTest {
        type.seed.forEach { exec(it) }
        // A chapter already at the address under the new owner, which the owner's unique address refuses.
        exec(type.seed.first { "'B/c1', 'C'" in it }.replace("(10, 2, 'B/c1'", "(11, 1, 'B/c1'"))

        runCatching {
            repository().regroup(
                type.contentType,
                SERIES,
                remove = emptyList(),
                add = listOf(VolumeOwner("B", VolumeOwner.WHOLE, "A")),
                volumes = emptyList(),
                unitOf = ::localChapterUnit,
            )
        }

        listOf(one(type.chapterQuery), one("SELECT count(*) FROM local_volume_owner")) shouldBe listOf("2|B/c1|1", "0")
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

        repository().rename(type.contentType, SeriesRename(2, "B", "Bee", mapOf("v1" to "Vol. 1")), null, ::toBee)

        rows("SELECT series || '|' || volume_key || '|' || label FROM local_volume") shouldBe listOf("Bee|Vol. 1|One")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a rename takes its journal row with it`(type: Type) = runTest {
        type.seed.forEach { exec(it) }
        val journalId = LocalFileJournalRepositoryImpl(database).insert(type.contentType, REPLACE_PLAN)

        repository().rename(type.contentType, RENAME, journalId, ::toBee)

        rows("SELECT _id FROM local_file_journal") shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a rename that fails leaves its journal row for the replay`(type: Type) = runTest {
        type.seed.forEach { exec(it) }
        // A chapter already at the address the rename takes, which the owner's unique address refuses.
        exec(type.seed.first { "'B/c1', 'C'" in it }.replace("(10, 2, 'B/c1'", "(11, 2, 'Bee/c1'"))
        val journalId = LocalFileJournalRepositoryImpl(database).insert(type.contentType, REPLACE_PLAN)

        runCatching {
            repository().rename(type.contentType, RENAME, journalId, ::toBee)
        }

        rows("SELECT _id FROM local_file_journal") shouldBe listOf(journalId.toString())
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `deleted volumes forget their saved order and the chip left on them`(type: Type) = runTest {
        val dbType = if (type.contentType == ContentType.NOVELS) 1 else 0
        exec("INSERT INTO local_volume(content_type, series, volume_key, position) VALUES ($dbType, 'B', 'v1', 0)")
        exec("INSERT INTO local_volume(content_type, series, volume_key, position) VALUES ($dbType, 'B', 'v2', 1)")
        exec("INSERT INTO local_volume_pick(content_type, entry_id, volume_key) VALUES ($dbType, 2, 'v1')")

        repository().forgetVolumes(type.contentType, 2, "B", mapOf("v1" to "B/v1/"))

        rows("SELECT volume_key FROM local_volume") + rows("SELECT volume_key FROM local_volume_pick") shouldBe
            listOf("v2")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `deleted volumes drop their kept reading state, other volumes' kept, and leave the rows to the listing`(
        type: Type,
    ) = runTest {
        type.seed.forEach { exec(it) }
        // The seeded chapter is B/c1; a chapter of volume v1 beside it, and tombstones of both volumes.
        exec(type.seed.first { "'B/c1', 'C'" in it }.replace("(10, 2, 'B/c1'", "(11, 2, 'B/v1/c2'"))
        exec(type.tombstone(2, "B/v1/c3"))
        exec(type.tombstone(2, "B/v10/c4"))

        repository().forgetVolumes(type.contentType, 2, "B", mapOf("v1" to "B/v1/"))

        rows(type.chapterUrls) + rows(type.tombstoneUrls) shouldBe listOf("B/c1", "B/v1/c2", "B/v10/c4")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a moved chapter's kept reading state moves with it`(type: Type) = runTest {
        // The chapter's file is gone, so it has no row, only its kept state at the old address.
        type.seed.filterNot { "'B/c1', 'C'" in it }.forEach { exec(it) }
        exec(type.tombstone(2, "B/c1", read = 1))

        regroup(type.contentType)

        rows(type.tombstoneState) shouldBe listOf("1|B/c1|1|0")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a kept state landing where a row already is folds onto the row and is spent`(type: Type) = runTest {
        // The row is at the destination already, unread, and the old address holds only the kept state, as a
        // refresh that slipped in between the files and the rows leaves them.
        type.seed.filterNot { "'B/c1', 'C'" in it }.forEach { exec(it) }
        exec(
            type.seed.first { "'B/c1', 'C'" in it }
                .replace("(10, 2, 'B/c1'", "(11, 1, 'B/c1'")
                .replace("'C', NULL, 1,", "'C', NULL, 0,")
                .replace("'C', 1, 0,", "'C', 0, 0,"),
        )
        exec(type.tombstone(2, "B/c1", read = 1))

        regroup(type.contentType)

        listOf(one(type.readAt("B/c1")), rows(type.tombstoneUrls).size.toString()) shouldBe listOf("1", "0")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a kept state landing on another folds onto it`(type: Type) = runTest {
        type.seed.filterNot { "'B/c1', 'C'" in it }.forEach { exec(it) }
        exec(type.tombstone(2, "B/c1", read = 1, progress = 3))
        exec(type.tombstone(1, "B/c1", read = 0, progress = 9))

        regroup(type.contentType)

        rows(type.tombstoneState) shouldBe listOf("1|B/c1|1|9")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a regroup settles only the keys it wrote`(type: Type) = runTest {
        type.seed.forEach { exec(it) }
        // A row and a kept state at one key elsewhere in the owner, as a restore in flight leaves them.
        exec(
            type.seed.first { "'B/c1', 'C'" in it }
                .replace("(10, 2, 'B/c1'", "(11, 1, 'A/X/c5'")
                .replace("'C', NULL, 1,", "'C', NULL, 0,")
                .replace("'C', 1, 0,", "'C', 0, 0,"),
        )
        exec(type.tombstone(1, "A/X/c5", read = 1))

        regroup(type.contentType)

        listOf(one(type.readAt("A/X/c5")), rows(type.tombstoneUrls).joinToString()) shouldBe listOf("0", "A/X/c5")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a renamed series takes its kept reading state along, found by the rename itself`(type: Type) = runTest {
        type.seed.forEach { exec(it) }
        // A chapter with no row, only its kept state, which no caller names: the row step must find it.
        exec(type.tombstone(2, "B/c9"))

        repository().rename(type.contentType, RENAME, null, ::toBee)

        rows(type.tombstoneUrls) shouldBe listOf("Bee/c9")
    }

    private fun repository() = LocalChapterMoveRepositoryImpl(database, ChapterTombstoneRepositoryImpl(database))

    /** Series B's whole folder going to series A, as a combine writes it. */
    private suspend fun regroup(type: ContentType) =
        repository().regroup(type, SERIES, emptyList(), listOf(B_IN_A), emptyList(), ::localChapterUnit)

    private suspend fun moveVolumes(vararg moves: VolumeRowMove) =
        repository().regroup(
            ContentType.MANGA,
            emptyMap(),
            emptyList(),
            emptyList(),
            moves.toList(),
            ::localChapterUnit,
        )

    private companion object {
        /** The rename's address mapping, series B to Bee, as the callers' url kernel would give it. */
        fun toBee(url: String) = "Bee/" + url.substringAfter('/')

        val SERIES = mapOf("A" to 1L, "B" to 2L)
        val B_IN_A = VolumeOwner("B", VolumeOwner.WHOLE, "A")
        val RENAME = SeriesRename(2, "B", "Bee", emptyMap())
        val REPLACE_PLAN = LocalFileJournalPlan.Replace("f", "n", "a", "p", "o", "u")
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
        ;

        /** A kept state at [url] of owner [owner], as a refresh leaves it. */
        fun tombstone(owner: Long, url: String, read: Int = 0, progress: Int = 0) = if (contentType ==
            ContentType.NOVELS
        ) {
            "INSERT INTO novel_chapter_tombstone(novel_id, url, chapter_number, read, bookmark, last_text_progress, " +
                "date_fetch, removed_at) VALUES ($owner, '$url', 1.0, $read, 0, $progress, 0, 0)"
        } else {
            "INSERT INTO manga_chapter_tombstone(manga_id, url, chapter_number, read, bookmark, last_page_read, " +
                "page_count, date_fetch, removed_at) VALUES ($owner, '$url', 1.0, $read, 0, $progress, 0, 0, 0)"
        }

        val chapterUrls get() = if (contentType == ContentType.NOVELS) {
            "SELECT url FROM novel_chapters ORDER BY url"
        } else {
            "SELECT remote_url FROM chapter ORDER BY remote_url"
        }

        /** Every chapter, a row's or a kept state's, as its owner and address. */
        val ownerAndUrl get() = if (contentType == ContentType.NOVELS) {
            "SELECT novel_id || '|' || url FROM novel_chapters UNION ALL " +
                "SELECT novel_id || '|' || url FROM novel_chapter_tombstone ORDER BY 1"
        } else {
            "SELECT manga_id || '|' || remote_url FROM chapter UNION ALL " +
                "SELECT manga_id || '|' || url FROM manga_chapter_tombstone ORDER BY 1"
        }

        val tombstoneUrls get() = if (contentType == ContentType.NOVELS) {
            "SELECT url FROM novel_chapter_tombstone ORDER BY url"
        } else {
            "SELECT url FROM manga_chapter_tombstone ORDER BY url"
        }

        val tombstoneState get() = if (contentType == ContentType.NOVELS) {
            "SELECT novel_id || '|' || url || '|' || read || '|' || last_text_progress FROM novel_chapter_tombstone"
        } else {
            "SELECT manga_id || '|' || url || '|' || read || '|' || last_page_read FROM manga_chapter_tombstone"
        }

        fun readAt(url: String) = if (contentType == ContentType.NOVELS) {
            "SELECT read FROM novel_chapters WHERE url = '$url'"
        } else {
            "SELECT user_read FROM chapter WHERE remote_url = '$url'"
        }
    }
}
