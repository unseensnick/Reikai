package eu.kanade.tachiyomi.data.backup.models

/**
 * [Backup]'s top-level protobuf field numbers, for the code that streams the file a field at a time
 * rather than through the serializer. `BackupFieldsTest` holds each to the annotation on [Backup].
 */
object BackupFields {
    const val MANGA: Int = 1
    const val CATEGORIES: Int = 2
    const val SOURCES: Int = 101
    const val PREFERENCES: Int = 104
    const val SOURCE_PREFERENCES: Int = 105
    const val EXTENSION_STORES: Int = 106
    const val NOVELS: Int = 700
    const val NOVEL_CATEGORIES: Int = 701
    const val NOVEL_MERGES: Int = 702
    const val NOVEL_UNMERGES: Int = 703
    const val EXTENSIONS: Int = 710
    const val MANGA_MERGES: Int = 711
    const val MANGA_UNMERGES: Int = 712
    const val CUSTOM_MANGA_INFO: Int = 713
    const val CUSTOM_NOVEL_INFO: Int = 714
    const val SAVED_SEARCHES: Int = 715
    const val FEED_ROWS: Int = 716
    const val NOVEL_SOURCES: Int = 717
    const val MERGE_GROUPS_STORED: Int = 718
    const val SORT_OVERRIDES_STORED: Int = 719
    const val KITSU_NATIVE_SCALE: Int = 720
}
