// RK: installed-extensions backup (Roadmap 9 follow-on). Net-new Reikai file: reinstalls the manga
// extensions a backup recorded. Must run after the extension repos are restored, since the available
// list is fetched from them. Installs are fired on the ExtensionManager's own scope (the standard
// installer, respecting the user's installer mode) so the restore job doesn't block on downloads or
// per-apk prompts. Extensions with no available match (repo missing) are returned for the restore log.
package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionRestorer(
    private val extensionManager: ExtensionManager = Injekt.get(),
) {

    /** Reinstall the backed-up extensions; returns the names of those that couldn't be matched. */
    suspend fun restore(backupExtensions: List<BackupExtension>): List<String> = coroutineScope {
        if (backupExtensions.isEmpty()) return@coroutineScope emptyList()

        extensionManager.findAvailableExtensions()
        // availableExtensionsFlow is a stateIn(Lazily) flow, so its .value lags behind the fetch we just
        // triggered; reading it synchronously here returned an empty list and reported every extension
        // as "repo missing". Await the populated emission instead, bounded so a genuinely empty result
        // (no repos / fetch failed) still falls through to the unmatched log.
        val available: List<Extension.Available> = withTimeoutOrNull(AVAILABLE_WAIT_MS) {
            extensionManager.availableExtensionsFlow.first { it.isNotEmpty() }
        }.orEmpty()
        val availableByPkg = available.associateBy { it.pkgName }
        val installedPkgs = extensionManager.installedExtensionsFlow.value.mapTo(HashSet()) { it.pkgName }

        val unmatched = mutableListOf<String>()
        backupExtensions.forEach { backupExtension ->
            if (backupExtension.pkgName in installedPkgs) return@forEach
            val match = availableByPkg[backupExtension.pkgName]
            if (match == null) {
                unmatched += backupExtension.name
                return@forEach
            }
            // RK: install on the restore's own scope and let coroutineScope await it, instead of firing
            // it onto the app-lifetime extensionManager.scope. The fire-and-forget installs kept landing
            // after the restore "finished", racing the trust evaluation (so a freshly reinstalled
            // extension could be seen once as untrusted and once as trusted, lingering in both lists) and
            // colliding with the user's own actions. Each install is bounded so one slow download can't
            // stall the whole restore.
            launch {
                withTimeoutOrNull(INSTALL_TIMEOUT_MS) {
                    runCatching {
                        extensionManager.installExtension(match)
                            .takeWhile { it != InstallStep.Installed && it != InstallStep.Error }
                            .collect()
                    }
                }
            }
        }
        unmatched
    }

    companion object {
        private const val AVAILABLE_WAIT_MS = 20_000L
        private const val INSTALL_TIMEOUT_MS = 90_000L
    }
}
