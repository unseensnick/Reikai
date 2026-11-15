// Installed-extensions backup. Net-new Reikai file: snapshots the installed manga and novel
// extension apps so the restore screen can list the ones to install. Reads the ExtensionManager's live
// installed lists.
package eu.kanade.tachiyomi.data.backup.create.creators

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.flow.first

@Inject
class ExtensionBackupCreator(
    private val extensionManager: ExtensionManager,
) {

    suspend operator fun invoke(): List<BackupExtension> {
        // Novel extension apps too, which the manager keeps apart from the manga ones. An app installed but
        // not loaded (untrusted, filtered out) is still installed, as the restore's check counts it.
        // The lists wait for the extension scan, as the source list this backup writes does.
        val installed = with(extensionManager) {
            loadedExtensionsFlow.first() + notLoadedExtensionsFlow.first() +
                loadedNovelExtensionsFlow.first() + notLoadedNovelExtensionsFlow.first()
        }
        return installed.map { BackupExtension(pkgName = it.pkgName, name = it.name) }
    }
}
