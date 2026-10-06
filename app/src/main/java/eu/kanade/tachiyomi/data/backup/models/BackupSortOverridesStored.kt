// Net-new Reikai file. Written on every backup, since its category flags carry the sort-override bit
// wherever a category keeps its own sort. Mihon and Reikai before 0.3.0 never wrote it: their flags alone
// were each category's sort, so a backup without this marker has those sorts marked on restore.
// Empty on purpose, like BackupMergeGroupsStored; its presence is the whole signal.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable

@Serializable
class BackupSortOverridesStored
