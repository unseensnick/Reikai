package reikai.data.db

import reikai.domain.db.Transactions
import tachiyomi.data.Database

class SqlDelightTransactions(
    private val database: Database,
) : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = database.transactionWithResult { block() }
}
