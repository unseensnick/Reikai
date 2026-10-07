// Installed-extensions backup. Net-new Reikai file. Mihon backs up only the extension REPOS, not which
// extensions are installed. This records the installed manga and novel extension apps so the restore
// screen can list the ones a device lacks; a restore installs none, since a backup can be anyone's file.
// pkgName is the match key and name what the list shows.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class BackupExtension(
    @ProtoNumber(1) var pkgName: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var versionCode: Long = 0,
    @ProtoNumber(4) var lang: String = "",
    @ProtoNumber(5) var isNsfw: Boolean = false,
    @ProtoNumber(6) var sources: List<Long> = emptyList(),
    @ProtoNumber(7) var repoUrl: String = "",
)
