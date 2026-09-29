package reikai.domain.novel

import reikai.domain.novel.model.Novel

/**
 * The novels in the library, by source and url: a browsed or searched novel has no row id, so this is
 * the only way a list can ask whether one is in. Both halves are needed, since two sources can list a
 * novel under the same path.
 */
@JvmInline
value class FavoritedNovels private constructor(private val keys: Set<Pair<String, String>>) {

    fun contains(sourceId: String, url: String): Boolean = (sourceId to url) in keys

    companion object {
        val None = FavoritedNovels(emptySet())

        fun of(novels: List<Novel>): FavoritedNovels =
            FavoritedNovels(novels.asSequence().filter { it.favorite }.mapTo(HashSet()) { it.source to it.url })
    }
}
