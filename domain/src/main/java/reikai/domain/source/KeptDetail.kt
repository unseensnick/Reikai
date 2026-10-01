package reikai.domain.source

import eu.kanade.tachiyomi.source.model.SManga

// What a refresh stores for a source-owned detail, the one rule both types follow: a source that sends nothing for
// it never wipes the stored value. Manga passes a null current value where its update coalesces to the stored one.

/** [parsed], unless the source sent no text (null or blank), then [current]. */
fun keptDetail(current: String?, parsed: String?): String? = parsed?.takeIf { it.isNotBlank() } ?: current

/** [parsed], unless the source sent no genre, then [current]. */
fun keptGenres(current: List<String>?, parsed: List<String>?): List<String>? =
    parsed?.takeIf { it.isNotEmpty() } ?: current

/** [parsed], unless the source could not tell the status (unknown, 0 for both types), then [current]. */
fun keptStatus(current: Long, parsed: Long): Long = parsed.takeIf { it != SManga.UNKNOWN.toLong() } ?: current
