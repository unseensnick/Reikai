package reikai.domain.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences

/** A fresh install downloads and removes novel chapters the way Mihon's defaults do for manga. */
class NovelDownloadDefaultsTest {

    private val store = InMemoryPreferenceStore()
    private val novel = NovelPreferences(store)
    private val manga = DownloadPreferences(store)

    @Test
    fun `every download and removal setting defaults to manga's`() {
        listOf(
            novel.removeAfterMarkedAsRead().defaultValue(),
            novel.removeAfterReadSlots().defaultValue(),
            novel.removeBookmarkedChapters().defaultValue(),
            novel.removeExcludeCategories().defaultValue(),
            novel.autoDownloadWhileReading().defaultValue(),
            novel.downloadNewChapters().defaultValue(),
            novel.downloadNewUnreadChaptersOnly().defaultValue(),
            novel.downloadNewChapterCategories().defaultValue(),
            novel.downloadNewChapterCategoriesExclude().defaultValue(),
        ) shouldBe listOf(
            manga.removeAfterMarkedAsRead.defaultValue(),
            manga.removeAfterReadSlots.defaultValue(),
            manga.removeBookmarkedChapters.defaultValue(),
            manga.removeExcludeCategories.defaultValue(),
            manga.autoDownloadWhileReading.defaultValue(),
            manga.downloadNewChapters.defaultValue(),
            manga.downloadNewUnreadChaptersOnly.defaultValue(),
            manga.downloadNewChapterCategories.defaultValue(),
            manga.downloadNewChapterCategoriesExclude.defaultValue(),
        )
    }
}
