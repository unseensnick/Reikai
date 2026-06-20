// RK: installed-sources backup (ROADMAP #9 follow-on). Net-new Reikai file: re-downloads the light-
// novel plugins a backup recorded. Novel plugins are JS files, not APKs, and their install state
// already rides the preference backup (ln_installed_plugin_urls). Only the .js files are missing after
// a restore, so this re-fetches each from its backed-up URL via the normal installer. Reads the URL set
// straight from the backed-up preference list, so it doesn't depend on the app-preference restore order.
package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import reikai.domain.novel.NovelPreferences
import reikai.novel.install.LnPluginInstaller
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelPluginRestorer(
    private val installer: LnPluginInstaller = Injekt.get(),
) {

    /** Reinstall every backed-up novel plugin; returns the URLs that failed to re-download. */
    suspend fun restore(backupPreferences: List<BackupPreference>): List<String> {
        val urls = backupPreferences
            .firstOrNull { it.key == NovelPreferences.INSTALLED_PLUGIN_URLS_KEY }
            ?.let { (it.value as? StringSetPreferenceValue)?.value }
            .orEmpty()
        if (urls.isEmpty()) return emptyList()

        val failed = mutableListOf<String>()
        urls.forEach { url ->
            runCatching { installer.installFromUrl(url) }
                .onFailure { failed += url }
        }
        return failed
    }
}
