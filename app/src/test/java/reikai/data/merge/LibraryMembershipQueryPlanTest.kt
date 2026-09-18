package reikai.data.merge

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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
 * The library-membership query re-runs on every write to its entry table, since it reads the favorite
 * flag and SQLite notifies per table, and two collectors keep it live whether or not the library is on
 * screen. So what each run costs is what has to stay small: it must walk the grouped entries and probe
 * each one's row, not walk every library favorite looking for the few that are grouped.
 */
class LibraryMembershipQueryPlanTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `the library membership query walks the groups, not the library`(type: ContentType) = runTest {
        Database.Schema.create(driver).await()
        val issued = mutableListOf<String>()
        MergeGroupRepositoryImpl(database(RecordingDriver(driver, issued))).getLibraryMembershipsAsFlow(type).first()

        val plan = queryPlan(issued.single())

        withClue(plan) {
            plan.first() shouldStartWith if (type == ContentType.MANGA) "SCAN MGM" else "SCAN MGN"
        }
    }

    private fun queryPlan(sql: String): List<String> =
        driver.executeQuery(
            identifier = null,
            sql = "EXPLAIN QUERY PLAN $sql",
            mapper = { cursor: SqlCursor ->
                QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(3)!!) })
            },
            parameters = 0,
        ).value

    /** Hands every query to [inner], keeping the SQL it was given. */
    private class RecordingDriver(private val inner: SqlDriver, private val issued: MutableList<String>) :
        SqlDriver by inner {
        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            issued += sql
            return inner.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }

    private fun database(driver: SqlDriver) = Database(
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
