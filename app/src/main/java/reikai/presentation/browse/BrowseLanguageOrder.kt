package reikai.presentation.browse

import eu.kanade.tachiyomi.util.system.LocaleHelper

/**
 * Orders two language sections, wherever a Browse list has them.
 *
 * Upstream's extension order, adopted across the whole surface so the Sources and Extensions lists
 * cannot disagree: multi-language first, then each language by its own name for itself (Deutsch,
 * English, Espanol), and sources declaring no language last.
 */
fun compareBrowseLanguages(a: String, b: String): Int = when {
    a == b -> 0
    a.isEmpty() -> 1
    b.isEmpty() -> -1
    else -> LocaleHelper.comparator(a, b)
}
