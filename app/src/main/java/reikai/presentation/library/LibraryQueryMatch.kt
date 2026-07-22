package reikai.presentation.library

/**
 * The shared library search grammar both the manga and novel libraries understand, so a search change is
 * written once. Supports an `id:` prefix (exact entry id), a `src:` prefix (delegated to [matchesSourceTerm]
 * because a manga source id is a Long and a novel source is a String slug), a plain-text match over
 * title/author/artist/description, and comma-separated, negatable terms matched against the source name and
 * genres. The manga library runs its metadata-source tag grammar as a manga-only pre-check before calling
 * this; novels have no such grammar, which is an accepted gap, not a faked feature.
 */
fun libraryQueryMatches(
    query: String,
    id: Long,
    title: String,
    author: String?,
    artist: String?,
    description: String?,
    genre: List<String>?,
    sourceName: String,
    matchesSourceTerm: (String) -> Boolean,
): Boolean {
    if (query.startsWith("id:", ignoreCase = true)) {
        return id == query.substringAfter("id:").toLongOrNull()
    }
    if (query.startsWith("src:", ignoreCase = true)) {
        return matchesSourceTerm(query.substringAfter("src:"))
    }
    return title.contains(query, ignoreCase = true) ||
        (author?.contains(query, ignoreCase = true) ?: false) ||
        (artist?.contains(query, ignoreCase = true) ?: false) ||
        (description?.contains(query, ignoreCase = true) ?: false) ||
        query.split(",").map { it.trim() }.all { term ->
            checkNegatableConstraint(term) {
                sourceName.contains(it, ignoreCase = true) ||
                    (genre?.any { g -> g.equals(it, ignoreCase = true) } ?: false)
            }
        }
}

/**
 * Runs [predicate] on [constraint]; a leading `-` is stripped off and the result inverted, so `-x` keeps
 * entries that do NOT match `x`.
 */
private fun checkNegatableConstraint(constraint: String, predicate: (String) -> Boolean): Boolean =
    if (constraint.startsWith("-")) {
        !predicate(constraint.substringAfter("-").trimStart())
    } else {
        predicate(constraint)
    }
