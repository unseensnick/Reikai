package reikai.domain.source

/**
 * Neutral identity for a source of either content type, the browse analogue of
 * [reikai.domain.entry.EntryId]: sealed, so a mismatched (type, id) cannot be constructed.
 *
 * The two id spaces cannot collide (a manga source is a `Long`, a plugin a `String` slug), so this
 * gives them one name rather than keeping them apart: a value naming both types at once, such as
 * the last-used source, cannot exist without it.
 */
sealed interface SourceKey {

    data class Manga(val id: Long) : SourceKey

    data class Novel(val id: String) : SourceKey

    /**
     * On-disk form, used by anything that persists a source across restarts. Both branches carry a
     * prefix so neither can be read as the other, and the id keeps whatever characters it has: a
     * plugin slug is taken as everything after the first separator.
     */
    fun serialize(): String = when (this) {
        is Manga -> "$MANGA_PREFIX$id"
        is Novel -> "$NOVEL_PREFIX$id"
    }

    companion object {
        private const val MANGA_PREFIX = "manga:"
        private const val NOVEL_PREFIX = "novel:"

        /** Reads [serialize]'s form back. Null for anything else, including an empty string, which is
         *  how "no source" is stored. */
        fun parse(value: String): SourceKey? = when {
            value.startsWith(MANGA_PREFIX) ->
                value.removePrefix(MANGA_PREFIX).toLongOrNull()?.let(::Manga)
            value.startsWith(NOVEL_PREFIX) ->
                value.removePrefix(NOVEL_PREFIX).takeIf { it.isNotEmpty() }?.let(::Novel)
            else -> null
        }
    }
}
