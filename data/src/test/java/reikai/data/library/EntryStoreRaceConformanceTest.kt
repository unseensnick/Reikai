package reikai.data.library

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.novel.model.Novel
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga

/**
 * Storing an entry a source lists resolves to the one row that source and url may have, even when
 * another writer stores it between the lookup and the insert. A trigger plays that writer: it stores
 * the same entry, under its own id, just before the insert under test lands.
 */
class EntryStoreRaceConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `storing an entry another writer stored first resolves to that writer's row`(type: Type) = runTest {
        driver.execute(null, type.racer, 0).await()

        type.store(database) shouldBe RACER_ID
    }

    enum class Type(val racer: String) {
        MANGA(
            "CREATE TRIGGER racer BEFORE INSERT ON manga WHEN NEW.id IS NOT $RACER_ID BEGIN " +
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, remote_update_strategy, " +
                "remote_memo, user_notes, user_reader_flags, user_chapter_flags, state_chapter_fetch_interval, " +
                "state_cover_last_modified, state_initialized) " +
                "VALUES ($RACER_ID, NEW.source_id, NEW.remote_url, 'racer', 0, 0, '{}', '', 0, 0, 0, 0, 0); END",
        ) {
            override suspend fun store(database: Database) = MangaRepositoryImpl(database)
                .insertNetworkManga(listOf(Manga.create().copy(source = 1L, url = "/m", title = "M")))
                .single()
                .id
        },
        NOVEL(
            "CREATE TRIGGER racer BEFORE INSERT ON novels WHEN NEW._id IS NOT $RACER_ID BEGIN " +
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags) " +
                "VALUES ($RACER_ID, NEW.source, NEW.url, 'racer', 0, 0, 0); END",
        ) {
            override suspend fun store(database: Database) = NovelRepositoryImpl(database)
                .insertOrGet(Novel.create().copy(source = "s", url = "/n", title = "N"))
                ?.id
        },
        ;

        abstract suspend fun store(database: Database): Long?
    }

    private companion object {
        const val RACER_ID = 42L
    }
}
