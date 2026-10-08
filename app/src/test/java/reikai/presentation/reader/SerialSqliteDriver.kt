package reikai.presentation.reader

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock

/**
 * An in-memory database for tests whose models query on real threads. `JdbcSqliteDriver(IN_MEMORY)` shares
 * one connection and one open transaction across every thread, so a query can run while another thread's
 * statement is mid-step, and sqlite-jdbc then fails the query with SQLITE_BUSY. The device never shares a
 * connection: each reader has its own WAL connection and a transaction holds the one writer. Here a lock
 * holds the connection for each statement and for a whole transaction, which stays with the thread that
 * began it, as on the device.
 */
class SerialSqliteDriver : JdbcDriver() {

    private val connection = DriverManager.getConnection(IN_MEMORY)
    private val lock = ReentrantLock()
    private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()

    override fun getConnection(): Connection {
        lock.lock()
        return connection
    }

    override fun closeConnection(connection: Connection) = lock.unlock()

    override fun close() = connection.close()

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) { queryKeys.forEach { listeners.getOrPut(it, ::linkedSetOf).add(listener) } }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) { queryKeys.forEach { listeners[it]?.remove(listener) } }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val toNotify = synchronized(listeners) { queryKeys.flatMapTo(linkedSetOf()) { listeners[it].orEmpty() } }
        toNotify.forEach(Query.Listener::queryResultsChanged)
    }

    private companion object {
        const val IN_MEMORY = "jdbc:sqlite:"
    }
}
