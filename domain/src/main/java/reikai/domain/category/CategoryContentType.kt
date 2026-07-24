package reikai.domain.category

/**
 * The `content_type` discriminator on the shared `categories` table: universal rows serve both content
 * types (the uncategorized system row 0), manga rows serve the manga library, novel rows the novel
 * library. Lets one category axis and one repository serve both types.
 */
object CategoryContentType {
    const val UNIVERSAL = 0L
    const val MANGA = 1L
    const val NOVEL = 2L
}
