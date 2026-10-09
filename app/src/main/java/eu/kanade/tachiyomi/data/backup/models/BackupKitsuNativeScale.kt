// Net-new Reikai file. Written on every backup, since Kitsu scores are stored on Kitsu's native 2-20 scale.
// Builds before that stored them out of 10 and never wrote it, so a restore decides from its absence whether
// to double them (KitsuBackupScoreScale). Empty on purpose, like BackupMergeGroupsStored.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable

@Serializable
class BackupKitsuNativeScale
