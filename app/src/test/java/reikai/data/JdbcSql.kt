package reikai.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/** Raw SQL against a test database, for seeding and reading what no repository query covers. */
internal suspend fun JdbcSqliteDriver.exec(sql: String) {
    execute(null, sql, 0).await()
}

/** Every row [sql] returns, each through [map]. */
internal suspend fun <T> JdbcSqliteDriver.rows(sql: String, map: (SqlCursor) -> T): List<T> = executeQuery(
    null,
    sql,
    { cursor -> QueryResult.Value(buildList { while (cursor.next().value) add(map(cursor)) }) },
    0,
).await()
