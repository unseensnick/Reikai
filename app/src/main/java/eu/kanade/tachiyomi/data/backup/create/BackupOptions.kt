package eu.kanade.tachiyomi.data.backup.create

import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.persistentListOf
import yokai.i18n.MR

data class BackupOptions(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val chapters: Boolean = true,
    val tracking: Boolean = true,
    val history: Boolean = true,
    val appPrefs: Boolean = true,
    val sourcePrefs: Boolean = true,
    val customInfo: Boolean = true,
    val readManga: Boolean = true,
    val includePrivate: Boolean = false,
    val novels: Boolean = true,
) {
    fun asBooleanArray() = booleanArrayOf(
        libraryEntries,
        categories,
        chapters,
        tracking,
        history,
        appPrefs,
        sourcePrefs,
        customInfo,
        readManga,
        includePrivate,
        novels,
    )

    companion object {
        fun getOptions() = persistentListOf(
            MR.strings.library_entries,
            MR.strings.categories,
            MR.strings.chapters,
            MR.strings.tracking,
            MR.strings.history,
            MR.strings.app_settings,
            MR.strings.source_settings,
            MR.strings.custom_manga_info,
            MR.strings.all_read_manga,
            MR.strings.backup_private_pref,
            MR.strings.light_novels,
        )

        fun getEntries() = persistentListOf(
            Entry(
                label = MR.strings.library_entries,
                getter = BackupOptions::libraryEntries,
                setter = { options, enabled -> options.copy(libraryEntries = enabled) },
            ),
            Entry(
                label = MR.strings.chapters,
                getter = BackupOptions::chapters,
                setter = { options, enabled -> options.copy(chapters = enabled) },
                enabled = { it.libraryEntries },
            ),
            Entry(
                label = MR.strings.tracking,
                getter = BackupOptions::tracking,
                setter = { options, enabled -> options.copy(tracking = enabled) },
                enabled = { it.libraryEntries },
            ),
            Entry(
                label = MR.strings.history,
                getter = BackupOptions::history,
                setter = { options, enabled -> options.copy(history = enabled) },
                enabled = { it.libraryEntries },
            ),
            Entry(
                label = MR.strings.custom_manga_info,
                getter = BackupOptions::customInfo,
                setter = { options, enabled -> options.copy(customInfo = enabled) },
                enabled = { it.libraryEntries },
            ),
            Entry(
                label = MR.strings.all_read_manga,
                getter = BackupOptions::readManga,
                setter = { options, enabled -> options.copy(readManga = enabled) },
                enabled = { it.libraryEntries },
            ),
            Entry(
                label = MR.strings.categories,
                getter = BackupOptions::categories,
                setter = { options, enabled -> options.copy(categories = enabled) },
            ),
            Entry(
                label = MR.strings.app_settings,
                getter = BackupOptions::appPrefs,
                setter = { options, enabled -> options.copy(appPrefs = enabled) },
            ),
            Entry(
                label = MR.strings.source_settings,
                getter = BackupOptions::sourcePrefs,
                setter = { options, enabled -> options.copy(sourcePrefs = enabled) },
            ),
            Entry(
                label = MR.strings.backup_private_pref,
                getter = BackupOptions::includePrivate,
                setter = { options, enabled -> options.copy(includePrivate = enabled) },
            ),
            Entry(
                label = MR.strings.light_novels,
                getter = BackupOptions::novels,
                setter = { options, enabled -> options.copy(novels = enabled) },
            ),
        )

        fun fromBooleanArray(array: BooleanArray): BackupOptions = BackupOptions(
            libraryEntries = array.getOrElse(0) { true },
            categories = array.getOrElse(1) { true },
            chapters = array.getOrElse(2) { true },
            tracking = array.getOrElse(3) { true },
            history = array.getOrElse(4) { true },
            appPrefs = array.getOrElse(5) { true },
            sourcePrefs = array.getOrElse(6) { true },
            customInfo = array.getOrElse(7) { true },
            readManga = array.getOrElse(8) { true },
            includePrivate = array.getOrElse(9) { false },
            // Older Reikai installs scheduled WorkManager jobs before this field existed; fall
            // back to true so an in-flight auto-backup still includes novels by default.
            novels = array.getOrElse(10) { true },
        )
    }

    data class Entry(
        val label: StringResource,
        val getter: (BackupOptions) -> Boolean,
        val setter: (BackupOptions, Boolean) -> BackupOptions,
        val enabled: (BackupOptions) -> Boolean = { true },
    )
}
