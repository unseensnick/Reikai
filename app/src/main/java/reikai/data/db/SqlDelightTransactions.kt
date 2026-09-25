package reikai.data.db

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.db.Transactions
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SqlDelightTransactions(
    private val database: Database,
) : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = database.transactionWithResult { block() }

    // The hook fires before transactionWithResult returns only for an outermost transaction; a nested
    // call's rollback happens later, at the enclosing one, which has to check for itself.
    override suspend fun <T> runThrowingOnRollback(block: suspend () -> T): T {
        var rolledBack = false
        val result = database.transactionWithResult {
            afterRollback { rolledBack = true }
            block()
        }
        check(!rolledBack) { "A nested write failed, so the transaction rolled back" }
        return result
    }
}
