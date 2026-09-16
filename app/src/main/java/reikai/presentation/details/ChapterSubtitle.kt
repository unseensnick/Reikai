package reikai.presentation.details

/**
 * The one rule for what a chapter row says under its title: in a merged group the source leads, then
 * the scanlator where the content type has one, and nothing at all when there is neither.
 *
 * Both details adapters call this. The reader's chapter list carries the same rule in its own two
 * providers and should adopt this when either is next touched.
 */
fun chapterSubtitle(sourceName: String?, scanlator: String? = null): String? =
    listOfNotNull(
        sourceName?.takeIf { it.isNotBlank() },
        scanlator?.takeIf { it.isNotBlank() },
    ).joinToString(" • ").ifEmpty { null }
