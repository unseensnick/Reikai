package reikai.data.notification

/**
 * Whether a notification must leave an entry unnamed: every entry while "Hide notification content" is
 * on, an adult one while "Hide adult content in notifications" is. Every manga and novel notifier that
 * names an entry decides here.
 */
fun isEntryHidden(hideAll: Boolean, hideAdult: Boolean, isAdult: Boolean): Boolean = hideAll || (hideAdult && isAdult)

/** The name a notification may show for an entry, none while [isEntryHidden]. */
fun shownEntryName(name: String, hideAll: Boolean, hideAdult: Boolean, isAdult: Boolean): String? =
    name.takeUnless { isEntryHidden(hideAll, hideAdult, isAdult) }

/**
 * The ids among [entries] a notification must leave unnamed, by [isEntryHidden]. [adultIdsAmong] is
 * asked only when the answer depends on it, since the manga verdict can wait on the extension scan.
 */
suspend fun <T> hiddenEntryIds(
    entries: List<T>,
    hideAll: Boolean,
    hideAdult: Boolean,
    id: (T) -> Long,
    adultIdsAmong: suspend (List<T>) -> Set<Long>,
): Set<Long> {
    val adultIds = if (hideAdult && !hideAll) adultIdsAmong(entries) else emptySet()
    return entries.map(id).filterTo(mutableSetOf()) { isEntryHidden(hideAll, hideAdult, it in adultIds) }
}

/**
 * A download error's title, null for the generic downloader title. "Hide notification content" is not
 * applied, as Mihon names the entry in its errors under it too. An adult entry hidden by the adult switch
 * takes its chapter with it, since a chapter name can carry the series'.
 */
fun downloadErrorTitle(entryName: String?, chapterName: String?, hideAdult: Boolean, isAdult: Boolean): String? {
    val name = entryName?.takeUnless { it.isBlank() || isEntryHidden(hideAll = false, hideAdult, isAdult) }
        ?: return null
    return chapterName?.let { "$name: $it" } ?: name
}
