package reikai.data.source

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
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

/**
 * The funnel that stores an entry a source lists keeps a lazy-load placeholder out of the stored cover,
 * and lets a real listing cover repair a library entry stuck on one, the same for both content types.
 * Library entries are seeded, since manga's upsert rewrites a non-library row's cover by itself.
 */
class ListedCoverConformanceTest {

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
    fun `a listing placeholder is never stored`(type: Type) = runTest {
        type.store(database, PLACEHOLDER)

        type.storedCover(database) shouldBe null
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a library entry stuck on a placeholder takes a real listing cover`(type: Type) = runTest {
        type.seedFavorite(database, PLACEHOLDER)

        type.store(database, COVER)

        type.storedCover(database) shouldBe COVER
    }

    /** Callers open or add the entry handed back, so it has to show the cover just stored. */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the entry handed back carries the cover it took`(type: Type) = runTest {
        type.seedFavorite(database, PLACEHOLDER)

        type.store(database, COVER) shouldBe COVER
    }

    /** A cover the write refused is not handed back, or the caller shows one the next read loses. */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a cover that failed to store is not handed back`(type: Type) = runTest {
        type.seedFavorite(database, PLACEHOLDER)
        driver.execute(
            null,
            "CREATE TRIGGER refuse BEFORE UPDATE ON ${type.table} WHEN NEW.${type.coverColumn} = '$COVER' " +
                "BEGIN SELECT RAISE(ABORT, 'refused'); END",
            0,
        ).await()

        type.store(database, COVER) shouldBe PLACEHOLDER
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a library entry with a real cover keeps it`(type: Type) = runTest {
        type.seedFavorite(database, COVER)

        type.store(database, OTHER_COVER)

        type.storedCover(database) shouldBe COVER
    }

    enum class Type(val table: String, val coverColumn: String) {
        MANGA("manga", "remote_cover") {
            override suspend fun seedFavorite(database: Database, cover: String) {
                MangaRepositoryImpl(
                    database,
                ).insertNetworkManga(listOf(manga(cover).copy(favoriteAt = Instant.fromEpochMilliseconds(1L))))
            }

            override suspend fun store(database: Database, cover: String) =
                NetworkToLocalManga(MangaRepositoryImpl(database))(manga(cover)).thumbnailUrl

            override suspend fun storedCover(database: Database) =
                MangaRepositoryImpl(database).getMangaByUrlAndSourceId(URL, 1L)?.thumbnailUrl

            private fun manga(cover: String) =
                Manga.create().copy(source = 1L, url = URL, title = "M", thumbnailUrl = cover)
        },
        NOVEL("novels", "thumbnail_url") {
            override suspend fun seedFavorite(database: Database, cover: String) {
                NovelRepositoryImpl(database).insert(novel(cover).copy(favoriteAt = Instant.fromEpochMilliseconds(1L)))
            }

            override suspend fun store(database: Database, cover: String) =
                NovelRepositoryImpl(database).insertOrGet(novel(cover))?.thumbnailUrl

            override suspend fun storedCover(database: Database) =
                NovelRepositoryImpl(database).getByUrlAndSource(URL, "s")?.thumbnailUrl

            private fun novel(cover: String) =
                Novel.create().copy(source = "s", url = URL, title = "N", thumbnailUrl = cover)
        },
        ;

        abstract suspend fun seedFavorite(database: Database, cover: String)

        /** Stores the entry as listed with [cover], and returns the cover of the entry handed back. */
        abstract suspend fun store(database: Database, cover: String): String?
        abstract suspend fun storedCover(database: Database): String?
    }

    private companion object {
        const val URL = "/entry"
        const val COVER = "https://site.example/cover.jpg"
        const val OTHER_COVER = "https://site.example/other.jpg"
        const val PLACEHOLDER = "https://site.example/wp-content/themes/madara/images/dflazy.jpg"
    }
}
