package reikai.data.backup

import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.PreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.interceptor.FLARESOLVERR_URL_KEY
import eu.kanade.tachiyomi.network.interceptor.carryFlareSolverrUserInfo
import reikai.domain.category.DEAD_LAST_USED_NOVEL_CATEGORY_KEY
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.DEAD_READER_AUTO_SCROLL_KEY
import reikai.domain.novel.DEAD_READER_PADDING_KEY
import reikai.domain.novel.DEAD_READER_TAP_TO_SCROLL_KEY
import reikai.domain.novel.DEAD_READER_TTS_BUTTON_KEYS
import reikai.domain.novel.DEAD_READER_TTS_ENABLED_KEY
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.domain.source.carryShowNsfwSource
import reikai.novel.content.NovelSnippets
import tachiyomi.core.common.preference.Preference

/**
 * Reikai's half of an App settings restore: retired keys carried into their replacements or skipped,
 * and keys a backup someone else made must not set. Only `PreferenceRestorer.restoreApp` runs it,
 * because Source settings restore with App settings off and must reach no app-wide setting. A fresh
 * install marks every migration done without running it, so each carry calls the migration's kernel.
 */
@Inject
class AppPreferenceCarry(
    private val novelPreferences: NovelPreferences,
    private val extensionSourcePreferences: SourcePreferences,
    private val networkPreferences: NetworkPreferences,
    private val trackPreferences: TrackPreferences,
    private val basePreferences: BasePreferences,
) {

    /** Carries the keys it owns, hands the rest to [write], then applies what must follow the write. */
    suspend fun restore(toRestore: List<BackupPreference>, write: suspend (List<BackupPreference>) -> Unit) {
        // An address carrying user:password@ never authenticated anything and sits in the backup in clear
        // text. Carried first because a move to another server deletes the saved login, which would
        // otherwise take any credential key the backup lists with it.
        toRestore.firstOrNull { it.key == FLARESOLVERR_URL_KEY }?.let { (_, value) ->
            (value as? StringPreferenceValue)?.let { networkPreferences.carryFlareSolverrUserInfo(it.value) }
        }
        // A restored plugin list can auto-load arbitrary .js the QuickJS host evaluates, so LnPluginInstaller
        // validates it against the restored repos first. The list itself is still written.
        if (toRestore.any { it.key == NovelPreferences.INSTALLED_PLUGIN_URLS_KEY }) {
            novelPreferences.pluginsNeedRevalidation().set(true)
        }
        write(toRestore.filterNot { (key, value) -> carry(key, value) })
        // The retired read-aloud switch still owes the bar its button, and the bar may restore after it.
        val readAloudWasOn = toRestore.any { (key, value) ->
            key == DEAD_READER_TTS_ENABLED_KEY && (value as? BooleanPreferenceValue)?.value == true
        }
        if (readAloudWasOn) novelPreferences.addReadAloudButtonToCustomisedBar()
    }

    /** True when [key] is handled here and must not be written as it stands. */
    private fun carry(key: String, value: PreferenceValue): Boolean {
        when (key) {
            DEAD_READER_PADDING_KEY ->
                (value as? IntPreferenceValue)?.let { novelPreferences.carryReaderPaddingToMargins(it.value) }
            // JavaScript runs in the chapter page beside the reader's bridge, so it comes back switched off.
            NovelPreferences.JS_SNIPPETS_KEY -> (value as? StringPreferenceValue)?.let { stored ->
                val snippets = NovelSnippets.decode(stored.value).map { it.copy(enabled = false) }
                novelPreferences.readerJsSnippets().set(NovelSnippets.encode(snippets))
            }
            ReikaiSourcePreferences.DEAD_SHOW_NSFW_SOURCE_KEY ->
                (value as? BooleanPreferenceValue)?.let { extensionSourcePreferences.carryShowNsfwSource(it.value) }
            DEAD_READER_TAP_TO_SCROLL_KEY ->
                (value as? BooleanPreferenceValue)?.let { novelPreferences.carryReaderTapToScroll(it.value) }
            DEAD_READER_AUTO_SCROLL_KEY ->
                (value as? BooleanPreferenceValue)?.let { novelPreferences.carryReaderAutoScroll(it.value) }
            // The address decides where the NovelList sign-in token goes, so a backup never moves it.
            trackPreferences.novelListApiUrl.key() -> Unit
            // A backup may be someone else's: it never picks a silent installer for later extension
            // installs, nor lets a chapter's own scripts run.
            basePreferences.extensionInstaller.key(), novelPreferences.readerKeepEmbeddedJs().key() -> Unit
            // Mihon never backs up app state, extension trust among it, so only a crafted backup carries any.
            else -> return key in SKIPPED_KEYS || Preference.isAppState(key) || SKIPPED_PREFIXES.any(key::startsWith)
        }
        return true
    }

    private companion object {
        val SKIPPED_KEYS = setOf(
            FLARESOLVERR_URL_KEY, // carried ahead of the write
            DEAD_READER_TTS_ENABLED_KEY, // carried after it
            // The retired merge prefs hold ids from the device the backup came from; the restorers rebuild
            // the groups from the backup's own {url, source} refs.
            ReikaiLibraryPreferences.MANGA_MANUAL_MERGES_KEY,
            ReikaiLibraryPreferences.MANGA_MANUAL_UNMERGES_KEY,
            ReikaiLibraryPreferences.NOVEL_MANUAL_MERGES_KEY,
            ReikaiLibraryPreferences.NOVEL_MANUAL_UNMERGES_KEY,
            // Retired keys nothing reads, which an old backup would otherwise resurrect after the cleanup
            // migration removed them. A pre-unification novel sort carries a flag layout nothing can
            // safely decode, so restoring it would be worse than useless.
            DEAD_LAST_USED_NOVEL_CATEGORY_KEY,
            ReikaiLibraryPreferences.DEAD_NOVEL_SORT_KEY,
            ReikaiLibraryPreferences.DEAD_NOVEL_RANDOM_SEED_KEY,
            ReikaiLibraryPreferences.DEAD_NOVEL_MERGE_ICONS_KEY,
            ReikaiLibraryPreferences.DEAD_NOVEL_GROUP_BY_KEY,
            ReikaiLibraryPreferences.DEAD_SHOW_EMPTY_CATEGORIES_KEY,
            ReikaiLibraryPreferences.DEAD_LAST_USED_NOVEL_PAGE_KEY,
            ReikaiLibraryPreferences.DEAD_LAST_USED_ALL_PAGE_KEY,
            ReikaiSourcePreferences.DEAD_UPDATES_FILTER_CATEGORIES_KEY,
            ReikaiSourcePreferences.DEAD_DOWNLOAD_CONTENT_TYPE_KEY,
            // The WebView developer tools let any computer with debugging rights inspect the app's
            // WebViews, so a backup never turns them on.
            NovelPreferences.WEBVIEW_DEV_TOOLS_KEY,
            // Armed above and cleared only by a revalidation; a backup's own false must never land.
            NovelPreferences.PLUGINS_NEED_REVALIDATION_KEY,
        ) + DEAD_READER_TTS_BUTTON_KEYS

        val SKIPPED_PREFIXES = listOf(
            ReikaiLibraryPreferences.DEAD_NOVEL_FILTER_KEY_PREFIX,
            ReikaiSourcePreferences.DEAD_UPDATES_FILTER_CATEGORY_SET_PREFIX,
            ReikaiSourcePreferences.DEAD_UPDATES_FILTER_NOVEL_CATEGORY_SET_PREFIX,
        )
    }
}
