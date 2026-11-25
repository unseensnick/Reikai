package reikai.data

/**
 * How many ids one IN list binds, under SQLite's 999-variable limit on older Android versions. A
 * repository method taking a list of ids reads through [readInChunks] itself, so no caller has to know it.
 */
const val MAX_SQL_IDS_PER_QUERY = 500

/** Every row [read] returns for [ids], one statement per [MAX_SQL_IDS_PER_QUERY] of them. */
suspend fun <T> readInChunks(ids: Collection<Long>, read: suspend (List<Long>) -> List<T>): List<T> =
    ids.chunked(MAX_SQL_IDS_PER_QUERY).flatMap { read(it) }
