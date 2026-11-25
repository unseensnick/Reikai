package reikai.data

/**
 * A LIKE pattern finding [term] anywhere, with its `%`, `_` and `\` taken literally, for a query that ends
 * `ESCAPE '\'`. Both library `chapter:` searches read through it.
 */
fun likeContaining(term: String): String =
    "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
