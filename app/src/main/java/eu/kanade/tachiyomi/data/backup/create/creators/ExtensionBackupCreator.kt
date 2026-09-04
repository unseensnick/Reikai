// RK: installed-extensions backup. Net-new Reikai file: snapshots the installed
// manga extensions so a restore can reinstall them. Reads the ExtensionManager's live installed list.
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
        val installed = withTimeoutOrNull(INSTALLED_WAIT_MS) {
            extensionManager.installedExtensionsFlow.first()
        }.orEmpty()
        return installed.map { extension ->
            BackupExtension(
                pkgName = extension.pkgName,
                name = extension.name,
                versionCode = extension.versionCode,
                lang = extension.lang,
                isNsfw = extension.isNsfw,
                sources = extension.sources.map { it.id },
                repoUrl = extension.store?.indexUrl.orEmpty(),
            )
        }
    }
}

private const val INSTALLED_WAIT_MS = 20_000L
