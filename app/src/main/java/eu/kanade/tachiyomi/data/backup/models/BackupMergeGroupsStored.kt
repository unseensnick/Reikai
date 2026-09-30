// Net-new Reikai file. Written on every backup whose merge groups are stored whole (fields 711 and 702).
// Reikai 0.3.x never wrote it: its same-title groups were derived live and never stored, so a backup
// without this marker has them rebuilt on restore. Empty on purpose; its presence is the whole signal.
// A length-delimited message rather than a bool, because BackupProtoReader streams only those.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable

@Serializable
class BackupMergeGroupsStored
