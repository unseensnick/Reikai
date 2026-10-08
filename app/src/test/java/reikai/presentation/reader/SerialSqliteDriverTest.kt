package reikai.presentation.reader

import app.cash.sqldelight.db.QueryResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class SerialSqliteDriverTest {

    // sqlite-jdbc follows a query that finds nothing with a commit of its own, which fails while another
    // thread's write is still reading out its RETURNING row on the shared connection.
    @Test
    fun `a query waits for a write still returning its row on another thread`() {
        val driver = SerialSqliteDriver()
        driver.execute(null, "CREATE TABLE t(x INTEGER)", 0)
        val returning = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = thread {
            driver.executeQuery(
                null,
                "INSERT INTO t VALUES (1) RETURNING x",
                { cursor ->
                    cursor.next()
                    returning.countDown()
                    release.await()
                    QueryResult.Unit
                },
                0,
            )
        }
        returning.await()
        var query: Result<*>? = null
        val reader = thread {
            query = runCatching { driver.executeQuery(null, "SELECT x FROM t WHERE x < 0", { QueryResult.Unit }, 0) }
        }
        reader.join(QUERY_HEAD_START_MS)
        release.countDown()
        writer.join()
        reader.join()
        driver.close()

        query?.exceptionOrNull() shouldBe null
    }

    private companion object {
        // Long enough for the query to reach the connection while the write still holds it.
        const val QUERY_HEAD_START_MS = 300L
    }
}
