package reikai.presentation.components

/**
 * The one rule for what a chapter row says under its title: in a merged group the source leads, then
 * the scanlator where the content type has one, and nothing at all when there is neither.
 *
 * Every caller that fills a chapter row goes through it: both details adapters and both reader chapter
 * sheets. The row draws its separator on a null check rather than a blank one, so a blank reaching it
 * renders a bullet with nothing after it; that is why the blanks are dropped here and not at the row.
 */
fun chapterSubtitle(sourceName: String?, scanlator: String? = null): String? =
    listOfNotNull(
        sourceName?.takeIf { it.isNotBlank() },
        scanlator?.takeIf { it.isNotBlank() },
    ).joinToString(" • ").ifEmpty { null }
