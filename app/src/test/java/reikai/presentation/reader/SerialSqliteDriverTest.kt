package reikai.presentation.reader

import app.cash.sqldelight.db.QueryResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

// Real threads on purpose: the driver's lock is a blocking monitor, which one virtual-time thread cannot contend.
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
        // Released once the query waits on the connection, or has already run without waiting for it.
        while (reader.state != Thread.State.WAITING && reader.state != Thread.State.TERMINATED) Thread.onSpinWait()
        release.countDown()
        writer.join()
        reader.join()
        driver.close()

        query?.exceptionOrNull() shouldBe null
    }
}
