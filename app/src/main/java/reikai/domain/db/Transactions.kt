package reikai.domain.db

/**
 * Run several writes as one unit of work, without handing the caller the database.
 *
 * Exists so a domain use case can say "these two writes commit together or neither does" while still
 * being constructible in a plain JVM test: a fake here is one line, where a mocked SQLDelight
 * `Database` silently swallows the transaction body and leaves the test asserting nothing.
 *
 * Nesting is safe: an inner call joins the enclosing transaction rather than opening a second one.
 */
interface Transactions {
    suspend fun <T> run(block: suspend () -> T): T
}
