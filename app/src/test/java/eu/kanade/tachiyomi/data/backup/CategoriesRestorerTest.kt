package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.category.CategoryContentType
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * A category that spans both libraries used to restore as two rows: this restorer wrote every backup
 * category as manga-only, so the novel restorer could not recognise the row and created its own copy.
 * These pin the content type actually reaching the insert, and the name matching that decides whether
 * an existing row is reused at all.
 */
class CategoriesRestorerTest {

    private val repository = mockk<CategoryRepository>()
    private val libraryPreferences = mockk<LibraryPreferences>(relaxed = true)

    private val restorer = CategoriesRestorer(
        categoryRepository = repository,
        getCategories = GetCategories(repository),
        libraryPreferences = libraryPreferences,
    )

    /** Restores [backup] over [device] and returns the rows the restorer inserted. */
    private suspend fun inserted(device: List<Category>, backup: List<BackupCategory>): List<Category> {
        coEvery { repository.getAll(any()) } returns device
        val inserted = slot<List<Category>>()
        coEvery { repository.insertAll(capture(inserted)) } just Runs
        restorer(backup)
        return inserted.captured
    }

    @Test
    fun `a category spanning both libraries is inserted with its own content type`() = runTest {
        inserted(emptyList(), listOf(BackupCategory(name = "Reading", contentType = CategoryContentType.UNIVERSAL)))
            .single().contentType shouldBe CategoryContentType.UNIVERSAL
    }

    @Test
    fun `a manga-only category is inserted as manga-only`() = runTest {
        inserted(emptyList(), listOf(BackupCategory(name = "Manga stuff", contentType = CategoryContentType.MANGA)))
            .single().contentType shouldBe CategoryContentType.MANGA
    }

    @Test
    fun `a category already on the device is reused rather than created again`() = runTest {
        inserted(
            listOf(category(id = 3, name = "Reading", contentType = CategoryContentType.UNIVERSAL)),
            listOf(BackupCategory(name = "Reading", contentType = CategoryContentType.UNIVERSAL)),
        ) shouldBe emptyList()
    }

    @Test
    fun `a backup written before the content type existed matches a category now spanning both libraries`() =
        runTest {
            // No content type on the wire, so it reads as manga-only and would miss a strict type match.
            inserted(
                listOf(category(id = 3, name = "Reading", contentType = CategoryContentType.UNIVERSAL)),
                listOf(BackupCategory(name = "Reading")),
            ) shouldBe emptyList()
        }

    @Test
    fun `a category the device does not have is created`() = runTest {
        inserted(
            listOf(category(id = 3, name = "Reading", contentType = CategoryContentType.UNIVERSAL)),
            listOf(BackupCategory(name = "Finished", contentType = CategoryContentType.MANGA)),
        ).map { it.name to it.contentType } shouldBe listOf("Finished" to CategoryContentType.MANGA)
    }

    private fun category(id: Long, name: String, contentType: Long) = Category(
        id = id,
        name = name,
        order = id,
        flags = 0L,
        contentType = contentType,
    )
}
