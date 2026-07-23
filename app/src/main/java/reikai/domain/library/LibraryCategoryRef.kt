package reikai.domain.library

import tachiyomi.domain.category.model.Category

/**
 * A library category together with the content type whose id space it belongs to.
 *
 * `categories._id` and `novel_categories._id` allocate independently, so the id 3 exists in both
 * tables meaning different things and a bare `Long` cannot say which one it names. Single-content-type
 * views get away with it because the active chip is an ambient answer, but that answer is easy to read
 * off the wrong model (a shipped fix scoped a novel update by the manga library's category id), and a
 * mixed [ContentType.ALL] list removes it entirely: two adjacent rows can belong to different id spaces.
 *
 * Pairing the two lets a call site dispatch on the category's own type instead of on ambient UI state.
 */
data class LibraryCategoryRef(
    val contentType: ContentType,
    val category: Category,
) {
    init {
        // ALL is a view filter over both id spaces, never an id space a category can live in.
        require(contentType != ContentType.ALL) {
            "A category belongs to exactly one content type's id space, so ALL cannot qualify one."
        }
    }

    val id: Long get() = category.id
}
