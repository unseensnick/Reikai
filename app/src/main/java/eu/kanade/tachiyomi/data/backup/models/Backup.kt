package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class Backup(
    @ProtoNumber(1) val backupManga: List<BackupManga>,
    @ProtoNumber(2) var backupCategories: List<BackupCategory> = emptyList(),
    // @ProtoNumber(100) var backupBrokenSources, legacy source model with non-compliant proto number,
    @ProtoNumber(101) var backupSources: List<BackupSource> = emptyList(),
    @ProtoNumber(104) var backupPreferences: List<BackupPreference> = emptyList(),
    @ProtoNumber(105) var backupSourcePreferences: List<BackupSourcePreferences> = emptyList(),
    @ProtoNumber(106) var backupExtensionStores: List<BackupExtensionStore> = emptyList(),
    // RK: light-novel library (Roadmap 9). Proto numbers in the 700 range stay clear of Mihon's
    // (1-106) and Komikku's fork additions (600/610). Merge/unmerge groups carry stable {url,source}
    // refs (see BackupNovelMerge) since the live merge prefs store IDs that change on restore.
    @ProtoNumber(700) var backupNovels: List<BackupNovel> = emptyList(),
    @ProtoNumber(701) var backupNovelCategories: List<BackupNovelCategory> = emptyList(),
    @ProtoNumber(702) var backupNovelMerges: List<BackupNovelMergeGroup> = emptyList(),
    @ProtoNumber(703) var backupNovelUnmerges: List<BackupNovelMergeGroup> = emptyList(),
    // RK: installed manga extensions (Mihon backs up only their repos). Novel plugins need no field
    // here: their install state already rides the preference backup (ln_installed_plugin_urls).
    @ProtoNumber(710) var backupExtensions: List<BackupExtension> = emptyList(),
    // RK: manga merge/unmerge groups as stable {url,source} refs (see BackupMangaMerge), since the live
    // merge prefs store IDs that change on restore (the manga twin of backupNovelMerges at 702/703).
    @ProtoNumber(711) var backupMangaMerges: List<BackupMangaMergeGroup> = emptyList(),
    @ProtoNumber(712) var backupMangaUnmerges: List<BackupMangaMergeGroup> = emptyList(),
    // RK: manga custom-info (non-destructive edits) as {url,source}-keyed entries, re-keyed on restore.
    @ProtoNumber(713) var backupCustomMangaInfo: List<BackupCustomMangaInfo> = emptyList(),
    // RK: novel custom-info (non-destructive edits), the novel twin of backupCustomMangaInfo.
    @ProtoNumber(714) var backupCustomNovelInfo: List<BackupCustomNovelInfo> = emptyList(),
)
