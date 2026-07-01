// RK: installed-extensions backup (Roadmap 9 follow-on). Net-new Reikai file: snapshots the installed
// manga extensions so a restore can reinstall them. Reads the ExtensionManager's live installed list.
package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.extension.ExtensionManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionBackupCreator(
    private val extensionManager: ExtensionManager = Injekt.get(),
) {

    operator fun invoke(): List<BackupExtension> {
        return extensionManager.installedExtensionsFlow.value.map { extension ->
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
