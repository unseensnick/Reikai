package eu.kanade.tachiyomi.data.backup.models

import eu.kanade.tachiyomi.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class Backup(
    @ProtoNumber(1) val backupManga: List<BackupManga>,
    @ProtoNumber(2) var backupCategories: List<BackupCategory> = emptyList(),
    //@ProtoNumber(100) var backupBrokenSources: List<BrokenBackupSource> = emptyList(),
    @ProtoNumber(101) var backupSources: List<BackupSource> = emptyList(),
    @ProtoNumber(104) var backupPreferences: List<BackupPreference> = emptyList(),
    @ProtoNumber(105) var backupSourcePreferences: List<BackupSourcePreferences> = emptyList(),
    // Light-novel rows. New in Reikai; upstream Yōkai's parser silently drops unknown protobuf
    // tags so cross-fork restore stays compatible (novels are simply not migrated). Tag picked
    // from the 200+ range to avoid colliding with manga (1, 16, 18), J2K (100, 101, 103-105),
    // SY (602), or J2K custom-fields (800-805).
    @ProtoNumber(200) var backupNovels: List<BackupNovel> = emptyList(),
) {

    companion object {
        val filenameRegex = """(${BuildConfig.APPLICATION_ID}|tachiyomi)?_\d+-\d+-\d+_\d+-\d+\.(tachibk|proto\.gz)""".toRegex()

        fun getBackupFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            return "${BuildConfig.APPLICATION_ID}_$date.tachibk"
        }
    }
}
