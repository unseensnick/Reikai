package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.category.CategoryContentType
import reikai.domain.library.FakeLibraryManualOrderRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

class CategoriesBackupCreatorTest {

    @Test
    fun `a Manual category is backed up with the sort it started from`() = runTest {
        val repository = mockk<CategoryRepository> {
            coEvery { getAll(any()) } returns listOf(Category(7, "Queue", 1, 0L, CategoryContentType.MANGA))
        }
        val manualOrder = FakeLibraryManualOrderRepository().apply { setBase(7, 42L) }

        CategoriesBackupCreator(GetCategories(repository), manualOrder)().single().manualBase shouldBe 42L
    }
}
