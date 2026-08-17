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
}
