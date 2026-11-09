// Installed-extensions backup. Net-new Reikai file: snapshots the installed manga and novel
// extension apps so the restore screen can list the ones to install. Reads the ExtensionManager's live
// installed lists.
package eu.kanade.tachiyomi.data.backup.create.creators

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Inject
class ExtensionBackupCreator(
    private val extensionManager: ExtensionManager,
) {

    // Bounded, because the installed list stays silent until the extension scan finishes and an
    // unbounded wait would hang the backup if that scan failed. An expired wait backs up no
    // extensions, which is what reading it early used to do anyway.
    suspend operator fun invoke(): List<BackupExtension> {
        // Novel extension apps too, which the manager keeps apart from the manga ones. An app installed but
        // not loaded (untrusted, filtered out) is still installed, as the restore's check counts it.
        val installed = withTimeoutOrNull(INSTALLED_WAIT_MS) {
            with(extensionManager) {
                loadedExtensionsFlow.first() + notLoadedExtensionsFlow.first() +
                    loadedNovelExtensionsFlow.first() + notLoadedNovelExtensionsFlow.first()
            }
        }.orEmpty()
        return installed.map { BackupExtension(pkgName = it.pkgName, name = it.name) }
    }
}

private const val INSTALLED_WAIT_MS = 20_000L
