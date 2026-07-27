package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
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
        getCategories = GetCategories(repository),
        categoryRepository = repository,
        libraryPreferences = libraryPreferences,
    )

    @Test
    fun `a category spanning both libraries is inserted with its own content type`() = runTest {
        coEvery { repository.getAll(any()) } returns emptyList()
        val contentType = slot<Long>()
        coEvery { repository.insert(any(), capture(contentType)) } returns 5L

        restorer(listOf(BackupCategory(name = "Reading", contentType = CategoryContentType.UNIVERSAL)))

        contentType.captured shouldBe CategoryContentType.UNIVERSAL
    }

    @Test
    fun `a manga-only category is inserted as manga-only`() = runTest {
        coEvery { repository.getAll(any()) } returns emptyList()
        val contentType = slot<Long>()
        coEvery { repository.insert(any(), capture(contentType)) } returns 5L

        restorer(listOf(BackupCategory(name = "Manga stuff", contentType = CategoryContentType.MANGA)))

        contentType.captured shouldBe CategoryContentType.MANGA
    }

    @Test
    fun `a category already on the device is reused rather than created again`() = runTest {
        coEvery { repository.getAll(any()) } returns listOf(
            category(id = 3, name = "Reading", contentType = CategoryContentType.UNIVERSAL),
        )

        restorer(listOf(BackupCategory(name = "Reading", contentType = CategoryContentType.UNIVERSAL)))

        coVerify(exactly = 0) { repository.insert(any(), any()) }
    }

    @Test
    fun `a backup written before the content type existed matches a category now spanning both libraries`() =
        runTest {
            coEvery { repository.getAll(any()) } returns listOf(
                category(id = 3, name = "Reading", contentType = CategoryContentType.UNIVERSAL),
            )

            // No content type on the wire, so it reads as manga-only and would miss a strict type match.
            restorer(listOf(BackupCategory(name = "Reading")))

            coVerify(exactly = 0) { repository.insert(any(), any()) }
        }

    @Test
    fun `a category the device does not have is created`() = runTest {
        coEvery { repository.getAll(any()) } returns listOf(
            category(id = 3, name = "Reading", contentType = CategoryContentType.UNIVERSAL),
        )
        coEvery { repository.insert(any(), any()) } returns 6L

        restorer(listOf(BackupCategory(name = "Finished", contentType = CategoryContentType.MANGA)))

        coVerify(exactly = 1) { repository.insert(any(), CategoryContentType.MANGA) }
    }

    private fun category(id: Long, name: String, contentType: Long) = Category(
        id = id,
        name = name,
        order = id,
        flags = 0L,
        contentType = contentType,
    )
}
