package reikai.domain.category

import reikai.domain.library.ContentType
import tachiyomi.domain.category.model.Category

/**
 * The categories an `All / Manga / Novels` chip shows: that library's own rows plus the universal ones,
 * which serve both. One rule for every surface offering the chip, so narrowing means the same thing on
 * the categories screen as it does in a filter picker.
 */
fun categoriesForContentType(categories: List<Category>, contentType: ContentType): List<Category> =
    when (contentType) {
        ContentType.ALL -> categories
        ContentType.MANGA -> categories.filterNot { it.contentType == CategoryContentType.NOVEL }
        ContentType.NOVELS -> categories.filterNot { it.contentType == CategoryContentType.MANGA }
    }

/** The content type a category created under this chip carries; All means universal. */
fun ContentType.toCategoryContentType(): Long = when (this) {
    ContentType.ALL -> CategoryContentType.UNIVERSAL
    ContentType.MANGA -> CategoryContentType.MANGA
    ContentType.NOVELS -> CategoryContentType.NOVEL
}
