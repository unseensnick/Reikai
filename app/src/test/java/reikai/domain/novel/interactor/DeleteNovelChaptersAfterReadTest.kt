package reikai.domain.novel.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.category.GetNovelCategories
import reikai.domain.download.NovelRemovableDownloads
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadManager
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.category.model.Category
import kotlin.time.Instant

/**
 * The manager is injected as a lambda because building it reads the saved download queue from the
 * database. Every novel mark-read site reaches it only through here, so this is where the deferral is
 * pinned: taking it as a plain parameter again would pay that read on library open.
 */
class DeleteNovelChaptersAfterReadTest {

    // Not InMemoryPreferenceStore: it hands out a fresh Preference per call over an immutable map, so a
    // set() is dropped and the next read returns the default.
    private val preferences = NovelPreferences(EmittingPreferenceStore())

    private var managerBuilds = 0
    private val manager = mockk<NovelDownloadManager>(relaxed = true)

    // Novel 2 sits in category 5; the categories mock answers none for any other, so it sits in Default.
    private val categories = mockk<GetNovelCategories>(relaxed = true) {
        coEvery { awaitByNovelId(2L) } returns listOf(Category(id = 5L, name = "Kept", order = 1L, flags = 0L))
    }
    private val removable = NovelRemovableDownloads(preferences, categories)

    private val interactor = DeleteNovelChaptersAfterRead(
        novelPreferences = preferences,
        removableDownloads = removable,
        downloadManager = {
            managerBuilds++
            manager
        },
    )

    private fun chapter(id: Long, novelId: Long = 1L) = NovelChapter(
        id = id, novelId = novelId, url = "", name = "", read = true, bookmark = false,
        lastTextProgress = 0L, chapterNumber = id.toDouble(), sourceOrder = id,
        dateFetch = Instant.fromEpochMilliseconds(0L),
        dateUpload = null, page = "",
    )

    @Test
    fun `constructing the interactor never builds the download manager`() {
        DeleteNovelChaptersAfterRead(
            novelPreferences = preferences,
            removableDownloads = removable,
            downloadManager = {
                managerBuilds++
                manager
            },
        )

        managerBuilds shouldBe 0
    }

    @Test
    fun `marking read with delete-after-read off never builds the download manager`() = runTest {
        preferences.removeAfterMarkedAsRead().set(false)

        interactor.await(listOf(chapter(1)))

        managerBuilds shouldBe 0
    }

    @Test
    fun `marking read with delete-after-read on deletes only what an excluded category does not keep`() = runTest {
        preferences.removeAfterMarkedAsRead().set(true)
        preferences.removeExcludeCategories().set(setOf("5"))

        interactor.await(listOf(chapter(1), chapter(2, novelId = 2L)))

        managerBuilds shouldBe 1
        verify { manager.deleteChapters(listOf(chapter(1))) }
    }

    @Test
    fun `an uncategorized novel keeps its downloads when Default is excluded`() = runTest {
        preferences.removeAfterMarkedAsRead().set(true)
        preferences.removeExcludeCategories().set(setOf("0"))

        interactor.await(listOf(chapter(1)))

        verify(exactly = 0) { manager.deleteChapters(any()) }
    }
}
