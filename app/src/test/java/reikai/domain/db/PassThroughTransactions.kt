package reikai.domain.db

/**
 * Runs the block, commits nothing: the unit tests for the migration engines assert what the block
 * does, so it has to actually run. A relaxed mock of [Transactions] would skip it and leave those
 * tests passing against an engine that writes nothing.
 */
object PassThroughTransactions : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}
