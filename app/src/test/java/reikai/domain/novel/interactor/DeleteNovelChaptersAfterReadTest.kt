package reikai.domain.novel.interactor

import io.kotest.matchers.shouldBe
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

/**
 * The manager is injected as a lambda because building it restores the persisted download queue and
 * resumes the drain. Every novel mark-read site reaches it only through here, so this is where the
 * deferral is pinned: taking it as a plain parameter again would resume downloads on library open.
 */
class DeleteNovelChaptersAfterReadTest {

    // Not InMemoryPreferenceStore: it hands out a fresh Preference per call over an immutable map, so a
    // set() is dropped and the next read returns the default.
    private val preferences = NovelPreferences(EmittingPreferenceStore())

    private var managerBuilds = 0
    private val manager = mockk<NovelDownloadManager>(relaxed = true)

    // The categories mock answers none, so the novel sits in Default.
    private val removable = NovelRemovableDownloads(preferences, mockk<GetNovelCategories>(relaxed = true))

    private val interactor = DeleteNovelChaptersAfterRead(
        novelPreferences = preferences,
        removableDownloads = removable,
        downloadManager = {
            managerBuilds++
            manager
        },
    )

    private fun chapter(id: Long) = NovelChapter(
        id = id, novelId = 1L, url = "", name = "", read = true, bookmark = false,
        lastTextProgress = 0L, chapterNumber = id.toDouble(), sourceOrder = id, dateFetch = 0L,
        dateUpload = 0L, page = "",
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
    fun `marking read with delete-after-read on builds the manager and deletes`() = runTest {
        preferences.removeAfterMarkedAsRead().set(true)

        interactor.await(listOf(chapter(1)))

        managerBuilds shouldBe 1
        verify { manager.deleteChapters(any()) }
    }

    @Test
    fun `an uncategorized novel keeps its downloads when Default is excluded`() = runTest {
        preferences.removeAfterMarkedAsRead().set(true)
        preferences.removeExcludeCategories().set(setOf("0"))

        interactor.await(listOf(chapter(1)))

        verify(exactly = 0) { manager.deleteChapters(any()) }
    }
}
