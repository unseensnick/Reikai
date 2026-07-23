package reikai.presentation.library

import reikai.domain.library.ContentType

/**
 * Orchestrates the library over its per-type [LibraryProvider]s, so the tab asks the engine which
 * behaviour to drive instead of deciding the content type itself at each call site.
 *
 * Shaped for a mixed list from the start: [providersFor] answers with every provider whose rows belong
 * in a view, which is one provider for Manga or Novels and both for [ContentType.ALL]. Only the
 * single-type case is wired today, because a mixed view additionally needs the manga and novel category
 * id spaces unified (they allocate independently, so the id 3 exists in both meaning different things).
 * An ALL view therefore fails loudly rather than silently rendering one content type; the library chip
 * does not offer All yet, so it is unreachable.
 */
class LibraryEngine(private val providers: List<LibraryProvider>) {

    /** Every provider contributing rows to a [contentType] view. Both of them for [ContentType.ALL]. */
    fun providersFor(contentType: ContentType): List<LibraryProvider> =
        providers.filter { contentType == ContentType.ALL || it.contentType == contentType }

    /** The behaviour driving a [contentType] view. */
    fun behaviorFor(contentType: ContentType): LibraryBehavior =
        providersFor(contentType).singleOrNull()
            ?: error("A mixed $contentType library needs one category id space across content types")
}
