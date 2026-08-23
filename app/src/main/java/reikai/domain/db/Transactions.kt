package reikai.domain.db

/**
 * Run several writes as one unit of work, without handing the caller the database: a domain use case can
 * say "these two writes commit together or neither does" and stay constructible in a plain JVM test,
 * where a fake is one line and a mocked SQLDelight `Database` silently swallows the transaction body,
 * leaving the test asserting nothing.
 *
 * Nesting is safe: an inner call joins the enclosing transaction rather than opening a second one.
 */
interface Transactions {
    suspend fun <T> run(block: suspend () -> T): T
}
