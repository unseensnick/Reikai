// Installed-extensions backup. Net-new Reikai file. Mihon backs up only the extension REPOS, not which
// extensions are installed. This records the installed manga and novel extension apps so a restore can
// list the ones a device lacks and install them from repos the user trusts (RestoreExtensionInstaller).
// pkgName is the match key and name what the list shows.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class BackupExtension(
    @ProtoNumber(1) var pkgName: String,
    @ProtoNumber(2) var name: String,
    // 3 to 7 (versionCode, lang, isNsfw, sources, repoUrl) are retired: written once, never read.
)
