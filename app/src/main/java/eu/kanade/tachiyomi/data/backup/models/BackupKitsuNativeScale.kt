// Net-new Reikai file. Written on every backup, since Kitsu scores are stored on Kitsu's native 2-20 scale.
// A backup without it is out of 10, or native but unmarked (Mihon's, and nightlies between the scale change
// and this marker), so a restore doubles an unmarked file's scores unless one is above 10
// (KitsuBackupScoreScale). Empty on purpose, like BackupMergeGroupsStored.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable

@Serializable
class BackupKitsuNativeScale
