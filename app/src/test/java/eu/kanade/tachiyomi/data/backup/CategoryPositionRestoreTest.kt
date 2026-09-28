package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.collections.shouldBeUnique
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.db.PassThroughTransactions
import reikai.domain.merge.RestoreMergeGroups
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.NewCategory

/**
 * Manga and novel categories share one table and one ordering, so a category a restore creates has to
 * take a position no other row holds, whichever library it belongs to (mihon 8bfa495f3).
 */
class CategoryPositionRestoreTest {

    @Test
    fun `a novel category restored beside manga categories takes a position of its own`() = runTest {
        MangaRestoreHarness.create().use { harness ->
            listOf("A", "B", "C").forEach { name -> harness.categories.insert(NewCategory(name = name, flags = 0)) }
            NovelRestorer(
                novelRepository = mockk(relaxed = true),
                novelChapterRepository = mockk(relaxed = true),
                categoryRepository = harness.categories,
                novelTrackRepository = mockk(relaxed = true),
                restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
                setCustomNovelInfo = mockk(relaxed = true),
                novelHistoryRepository = mockk(relaxed = true),
            ).restoreCategories(listOf(BackupNovelCategory(name = "Novels", order = 0)))

            harness.categories.getUnfiltered()
                .filterNot(Category::isSystemCategory)
                .map { it.order }
                .shouldBeUnique()
        }
    }
}
