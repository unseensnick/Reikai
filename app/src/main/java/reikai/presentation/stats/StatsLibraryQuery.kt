package reikai.presentation.stats

import reikai.presentation.library.quotedQueryValue
import reikai.presentation.library.sourceKeyQuery
import reikai.presentation.library.statusQueryWord
import tachiyomi.domain.category.model.Category

/**
 * The library search that lists a details row's series, or null for a row search cannot express (a start
 * year, a name holding both quote marks). [trackerName] and [categoryName] name an id; a null answer leaves the
 * row unopenable. `tag:` and
 * `lang:` match a part of a value, so their search can also find a longer tag or language holding it.
 */
fun StatsGroupKey.libraryQuery(trackerName: (Long) -> String?, categoryName: (Long) -> String?): String? =
    when (this) {
        is StatsGroupKey.Status -> "status:${statusQueryWord(code)}"
        is StatsGroupKey.Score -> when (bar) {
            null -> "-score>0"
            1 -> "score>0 score<1.5"
            10 -> "score>=9.5"
            // The band scoreBar rounds into the bar.
            else -> "score>=${bar - 0.5} score<${bar + 0.5}"
        }
        is StatsGroupKey.Length -> when (chapters.last) {
            Long.MAX_VALUE -> "total>=${chapters.first}"
            else -> "total>=${chapters.first} total<=${chapters.last}"
        }
        is StatsGroupKey.Tracker -> when (trackerId) {
            null -> "tracker:\"\""
            else -> trackerName(trackerId)?.let(::quotedQueryValue)?.let { "tracker:$it" }
        }
        is StatsGroupKey.Source -> sourceKeyQuery(key)
        is StatsGroupKey.Category -> if (id ==
            Category.UNCATEGORIZED_ID
        ) {
            "category:\"\""
        } else {
            categoryName(id)?.let(::quotedQueryValue)?.let { "category:$it" }
        }
        is StatsGroupKey.Tag -> quotedQueryValue(name)?.let { "tag:$it" }
        is StatsGroupKey.Language -> "lang:\"$code\""
        is StatsGroupKey.StartYear -> null
    }
