package reikai.domain.novel

import reikai.domain.novel.model.Novel

/**
 * The novels in the library, by source and url: a browsed or searched novel has no row id, so this is
 * the only way a list can ask whether one is in. Both halves are needed, since two sources can list a
 * novel under the same path.
 */
@JvmInline
value class FavoritedNovels private constructor(private val rows: Map<Pair<String, String>, Novel>) {

    fun contains(sourceId: String, url: String): Boolean = (sourceId to url) in rows

    /** The library row behind a listed novel, whose id and cover state a result draws its cover from. */
    fun stored(sourceId: String, url: String): Novel? = rows[sourceId to url]

    companion object {
        val None = FavoritedNovels(emptyMap())

        fun of(novels: List<Novel>): FavoritedNovels =
            FavoritedNovels(novels.asSequence().filter { it.favorite }.associateBy { it.source to it.url })
    }
}
