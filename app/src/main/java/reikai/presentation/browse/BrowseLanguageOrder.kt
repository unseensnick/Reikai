package reikai.presentation.browse

import android.content.Context
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.icons.FlagEmoji

/**
 * Orders two language sections, wherever a Browse list has them.
 *
 * Upstream's extension order, adopted across the whole surface so the Sources and Extensions lists
 * cannot disagree: multi-language first, then each language by its own name for itself (Deutsch,
 * English, Espanol), and sources declaring no language last. Two codes Android names alike ("in" and
 * "id") fall back to the code, so a sorted map keyed by this keeps both groups.
 */
fun compareBrowseLanguages(a: String, b: String): Int = when {
    a == b -> 0
    a.isEmpty() -> 1
    b.isEmpty() -> -1
    // The local source's own group belongs with the leftovers at the end, not sorted among real
    // languages under the letter its code happens to start with.
    a == OTHER_LANGUAGE -> 1
    b == OTHER_LANGUAGE -> -1
    else -> LocaleHelper.comparator(a, b).takeIf { it != 0 } ?: a.compareTo(b)
}

/** The language the local source declares, which names no language at all. */
private const val OTHER_LANGUAGE = "other"

/**
 * The heading for a language section. Android names a language only from its tag, and a plugin can
 * declare something that is not one, which would otherwise leave a section headed by nothing at all.
 * Sources declaring none are headed Unknown, since Android reads an empty tag as the device's language.
 */
fun browseLanguageLabel(lang: String, context: Context): String =
    if (lang.isBlank()) {
        context.stringResource(MR.strings.unknown)
    } else {
        LocaleHelper.getSourceDisplayName(lang, context).ifBlank { lang }
    }

/**
 * A source's language under its name, or null for a source declaring none: Android reads an empty
 * tag as the device's own language, which would name a language the source never claimed.
 */
fun sourceLanguageName(lang: String, context: Context): String? =
    lang.takeIf { it.isNotBlank() }?.let { browseLanguageLabel(it, context) }

/** The language line under a Sources row, led by its flag; left blank for a source declaring none. */
fun sourceLanguageLabel(lang: String, context: Context): String =
    sourceLanguageName(lang, context)?.let { "${FlagEmoji.getEmojiLangFlag(lang)} $it" }.orEmpty()

/**
 * The sources filter's language order, both content types' halves of the one screen: languages switched on
 * before those switched off ([isOff]), as Mihon lists its enabled languages first, each by [compareBrowseLanguages].
 */
fun sourcesFilterLanguageOrder(isOff: (String) -> Boolean): Comparator<String> =
    compareBy<String> { isOff(it) }.then(Comparator(::compareBrowseLanguages))

/** Within one language of the sources filter, both types' sources switched off last, then by name ignoring case. */
fun <T> sourcesFilterSourceOrder(isOff: (T) -> Boolean, name: (T) -> String): Comparator<T> =
    compareBy<T> { isOff(it) }.thenBy(String.CASE_INSENSITIVE_ORDER, name)
