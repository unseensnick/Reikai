// RK: installed-extensions backup (Roadmap 9 follow-on). Net-new Reikai file. Mihon backs up only the
// extension REPOS, not which extensions are installed. This records the installed manga extensions so a
// restore can re-fetch and reinstall them (and surface any whose repo is missing). pkgName is the match
// key; the rest powers the actionable "couldn't reinstall" list (see the restore-path onboarding item).
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
