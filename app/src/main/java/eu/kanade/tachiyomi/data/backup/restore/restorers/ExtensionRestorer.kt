// RK: installed-extensions backup (ROADMAP #9 follow-on). Net-new Reikai file: reinstalls the manga
// extensions a backup recorded. Must run after the extension repos are restored, since the available
// list is fetched from them. Installs are fired on the ExtensionManager's own scope (the standard
// installer, respecting the user's installer mode) so the restore job doesn't block on downloads or
// per-apk prompts. Extensions with no available match (repo missing) are returned for the restore log.
package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionRestorer(
    private val extensionManager: ExtensionManager = Injekt.get(),
) {

    /** Reinstall the backed-up extensions; returns the names of those that couldn't be matched. */
    suspend fun restore(backupExtensions: List<BackupExtension>): List<String> {
        if (backupExtensions.isEmpty()) return emptyList()

        extensionManager.findAvailableExtensions()
        val availableByPkg = extensionManager.availableExtensionsFlow.value.associateBy { it.pkgName }
        val installedPkgs = extensionManager.installedExtensionsFlow.value.mapTo(HashSet()) { it.pkgName }

        val unmatched = mutableListOf<String>()
        backupExtensions.forEach { backupExtension ->
            if (backupExtension.pkgName in installedPkgs) return@forEach
            val available = availableByPkg[backupExtension.pkgName]
            if (available == null) {
                unmatched += backupExtension.name
                return@forEach
            }
            extensionManager.scope.launch {
                runCatching {
                    extensionManager.installExtension(available)
                        .takeWhile { it != InstallStep.Installed && it != InstallStep.Error }
                        .collect()
                }
            }
        }
        return unmatched
    }
}
