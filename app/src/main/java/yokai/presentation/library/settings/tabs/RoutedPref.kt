package yokai.presentation.library.settings.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import eu.kanade.tachiyomi.core.preference.Preference

/**
 * Picks one of two parallel [Preference]s based on [routeToNovel]. Reads through the result
 * are reactive (`pref.collectAsState()`) and writes go to the picked pref, so flipping
 * [routeToNovel] swaps both sides atomically.
 *
 * The Display sheet tab composables use this with `routeToNovel = isNovelTab && !shared`
 * (where shared = [yokai.domain.base.BasePreferences.useSharedLibraryDisplayPrefs]) so a
 * toggle on the Novels-tab Display sheet writes to its own
 * [yokai.domain.novel.NovelPreferences] key in independent mode, or to the manga key in
 * shared mode (the default).
 */
@Composable
internal fun <T> rememberRoutedPref(
    routeToNovel: Boolean,
    mangaPref: Preference<T>,
    novelPref: Preference<T>,
): Preference<T> = remember(routeToNovel, mangaPref, novelPref) {
    if (routeToNovel) novelPref else mangaPref
}
