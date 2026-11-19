package reikai.presentation.migrate.flow

import reikai.domain.entry.EntryCustomInfo
import reikai.domain.entry.seriesAltTitles

/** How many of a series' other titles the fallback searches, so a source pays at most one pass more. */
internal const val MAX_FALLBACK_TITLES = 3

/**
 * The primary title searched exactly as before; only when it finds nothing, one scored pass over the
 * series' other titles, capped. [single] and [multi] are each engine's own search, so the rule exists once.
 */
suspend fun <T : Any> searchPrimaryThenFallback(
    primary: String,
    others: List<String>,
    single: suspend (String) -> T?,
    multi: suspend (List<String>) -> T?,
): T? = single(primary) ?: seriesAltTitles(primary, others, emptyList()).take(MAX_FALLBACK_TITLES)
    .takeIf { it.isNotEmpty() }?.let { multi(it) }

/** The titles besides [MigrationEntry.title] the entry is known by, by the rule the fallback above searches. */
val MigrationEntry.alternativeTitles: List<String> get() = seriesAltTitles(title, otherTitles, emptyList())

/**
 * Every title a migrating entry is known by, the Edit info title first: its [custom] title (else its
 * own), then [seriesAltTitles] over its merge group's [memberTitles] and the stored ones.
 */
fun migrationOtherTitles(ownTitle: String, custom: EntryCustomInfo?, memberTitles: List<String>): List<String> {
    val main = custom?.title ?: ownTitle
    return listOf(main) + seriesAltTitles(main, memberTitles, custom?.altTitles.orEmpty())
}

/** Each title of [id]'s merge group in [relatedIds] order, its own row's [ownTitle] reused rather than read again. */
suspend fun migrationMemberTitles(
    id: Long,
    ownTitle: String,
    relatedIds: LongArray,
    titleOf: suspend (Long) -> String?,
): List<String> = relatedIds.asList().mapNotNull { if (it == id) ownTitle else titleOf(it) }
