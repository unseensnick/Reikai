package reikai.presentation.browse

import android.content.Context
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
    // The local source's own group belongs with the leftovers at the end, not sorted among real
    // languages under the letter its code happens to start with.
    a == OTHER_LANGUAGE -> 1
    b == OTHER_LANGUAGE -> -1
    else -> LocaleHelper.comparator(a, b)
}

/** The language the local source declares, which names no language at all. */
private const val OTHER_LANGUAGE = "other"

/**
 * The heading for a language section. Android names a language only from its tag, and a plugin can
 * declare something that is not one, which would otherwise leave a section headed by nothing at all.
 */
fun browseLanguageLabel(lang: String, context: Context): String =
    LocaleHelper.getSourceDisplayName(lang, context).ifBlank { lang }
