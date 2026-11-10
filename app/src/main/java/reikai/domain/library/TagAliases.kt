package reikai.domain.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** One tag the user declared the same as another: [from] reads as [to] wherever the library compares tags. */
@Serializable
data class TagAlias(val from: String, val to: String)

/**
 * The user's tag aliases, the one rule every place that compares tags applies: the tag filter and its
 * picker, `tag:` search, Group by tag, Statistics and the recommendation taste profile. Adult detection
 * and gallery tags never take them, since those read a source's exact tags. One hop only: [addTagAlias]
 * re-points an alias at the end of a chain, so a tag never resolves to another alias.
 */
data class TagAliases(val entries: List<TagAlias>) {
    private val byKey = entries.associate { tagKey(it.from) to it.to.trim() }

    fun resolve(tag: String): String = byKey[tagKey(tag)] ?: tag

    /** [tags] as their aliases name them, each tag once. */
    fun resolveAll(tags: List<String>): List<String> =
        if (byKey.isEmpty()) tags else tags.map(::resolve).distinctBy(::tagKey)

    /** A stored [tagKey] as its alias's key. */
    fun resolveKey(key: String): String = byKey[key]?.let(::tagKey) ?: key

    companion object {
        val NONE = TagAliases(emptyList())
    }
}

fun ReikaiLibraryPreferences.tagAliasesFlow(): Flow<TagAliases> = tagAliases.changes().map(::TagAliases)

fun ReikaiLibraryPreferences.currentTagAliases() = TagAliases(tagAliases.get())

/**
 * Makes [from] read as [to]. An alias already pointing at [from] is moved to [to], and a [to] that is
 * itself an alias is followed first, so every alias stays one hop.
 */
fun ReikaiLibraryPreferences.addTagAlias(from: String, to: String) {
    val list = tagAliases.get()
    val target = TagAliases(list).resolve(to).trim()
    if (tagKey(from) == tagKey(target)) return
    // The tag filter keeps working on the tag it now reads as.
    val fromKey = tagKey(from)
    val toKey = tagKey(target)
    if (fromKey in filterTagsInclude.get()) filterTagsInclude.set(filterTagsInclude.get() - fromKey + toKey)
    if (fromKey in filterTagsExclude.get()) filterTagsExclude.set(filterTagsExclude.get() - fromKey + toKey)
    tagAliases.set(
        list.filterNot { tagKey(it.from) == tagKey(from) }
            .map { if (tagKey(it.to) == tagKey(from)) it.copy(to = target) else it } +
            TagAlias(from.trim(), target),
    )
}

fun ReikaiLibraryPreferences.removeTagAlias(from: String) {
    tagAliases.set(tagAliases.get().filterNot { tagKey(it.from) == tagKey(from) })
}
