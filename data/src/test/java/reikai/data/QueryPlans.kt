package reikai.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/** The detail column of SQLite's `EXPLAIN QUERY PLAN` for [sql], one line per step, in plan order. */
fun SqlDriver.queryPlan(sql: String): List<String> =
    executeQuery(
        identifier = null,
        sql = "EXPLAIN QUERY PLAN $sql",
        mapper = { cursor: SqlCursor ->
            QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(3)!!) })
        },
        parameters = 0,
    ).value

/** Hands every query to [inner], keeping the SQL it was given, so a test can plan what a repository ran. */
class RecordingDriver(private val inner: SqlDriver, private val issued: MutableList<String>) : SqlDriver by inner {
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
