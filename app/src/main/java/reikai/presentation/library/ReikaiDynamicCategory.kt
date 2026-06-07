package reikai.presentation.library

import tachiyomi.domain.category.model.Category

/**
 * Mihon's [Category] is immutable with only id/name/order/flags, so a dynamic-grouping category
 * (group by source / language / tag / etc.) cannot store the extra fields the Yōkai-era fork kept
 * (sourceId, langId, isDynamic). Instead a synthetic dynamic category gets a **negative id** and
 * encodes its metadata into [Category.name]; this object decodes it.
 *
 * The encoded name doubles as the stable collapse key persisted in
 * `collapsedDynamicCategories` (so it survives across rebuilds). Splitter strings are kept
 * identical to the fork for in-place-upgrade continuity of that preference.
 */
object ReikaiDynamicCategory {

    const val SOURCE_SPLITTER = "◘•◘"
    const val LANG_SPLITTER = "⨼⨦⨠"

    /** A synthetic dynamic category, distinguished from real DB categories by its negative id. */
    fun isDynamic(category: Category): Boolean = category.id < 0

    /** Stable collapse key: the encoded name itself. */
    fun headerKey(category: Category): String = category.name

    /** Human-facing name with the encoded source-id / lang-code stripped off. */
    fun displayName(category: Category): String {
        val name = category.name
        return when {
            SOURCE_SPLITTER in name -> name.substringBefore(SOURCE_SPLITTER)
            LANG_SPLITTER in name -> name.substringAfter(LANG_SPLITTER)
            else -> name
        }
    }

    /** Source id for a BY_SOURCE group, or null if this isn't a source group. */
    fun sourceId(category: Category): Long? =
        if (SOURCE_SPLITTER in category.name) category.name.substringAfter(SOURCE_SPLITTER).toLongOrNull() else null

    /** Language code for a BY_LANGUAGE group, or null if this isn't a language group. */
    fun langId(category: Category): String? =
        if (LANG_SPLITTER in category.name) category.name.substringBefore(LANG_SPLITTER) else null
}
