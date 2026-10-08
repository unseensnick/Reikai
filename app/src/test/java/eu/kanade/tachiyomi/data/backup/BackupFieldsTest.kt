package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupFields
import io.kotest.matchers.shouldBe
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Test

/**
 * The streamed writer, the restore and the pre-restore check read and write top-level fields by number,
 * past the serializer. Each number they use has to be the one [Backup] declares for that field.
 */
class BackupFieldsTest {

    @Test
    fun `every field number the stream uses is the one Backup declares`() {
        val descriptor = Backup.serializer().descriptor
        val declared = (0 until descriptor.elementsCount).associate { index ->
            descriptor.getElementName(index) to
                descriptor.getElementAnnotations(index).filterIsInstance<ProtoNumber>().single().number
        }
        declared shouldBe mapOf(
            "backupManga" to BackupFields.MANGA,
            "backupCategories" to BackupFields.CATEGORIES,
            "backupSources" to BackupFields.SOURCES,
            "backupPreferences" to BackupFields.PREFERENCES,
            "backupSourcePreferences" to BackupFields.SOURCE_PREFERENCES,
            "backupExtensionStores" to BackupFields.EXTENSION_STORES,
            "backupNovels" to BackupFields.NOVELS,
            "backupNovelCategories" to BackupFields.NOVEL_CATEGORIES,
            "backupNovelMerges" to BackupFields.NOVEL_MERGES,
            "backupNovelUnmerges" to BackupFields.NOVEL_UNMERGES,
            "backupExtensions" to BackupFields.EXTENSIONS,
            "backupMangaMerges" to BackupFields.MANGA_MERGES,
            "backupMangaUnmerges" to BackupFields.MANGA_UNMERGES,
            "backupCustomMangaInfo" to BackupFields.CUSTOM_MANGA_INFO,
            "backupCustomNovelInfo" to BackupFields.CUSTOM_NOVEL_INFO,
            "backupSavedSearches" to BackupFields.SAVED_SEARCHES,
            "backupFeedRows" to BackupFields.FEED_ROWS,
            "backupNovelSources" to BackupFields.NOVEL_SOURCES,
            "backupMergeGroupsStored" to BackupFields.MERGE_GROUPS_STORED,
            "backupSortOverridesStored" to BackupFields.SORT_OVERRIDES_STORED,
        )
    }
}
