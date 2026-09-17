package reikai.data.novel.update

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_READ
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_OUTSIDE_RELEASE_PERIOD

/** The novel library update skips a novel by the manga job's rules. */
class NovelSmartUpdateTest {

    private val window = 1_000L to 2_000L

    @Test
    fun `a novel due after the window is skipped under the release period restriction`() = runTest {
        novelPassesSmartUpdate(Novel.create().copy(nextUpdate = 3_000L), setOf(MANGA_OUTSIDE_RELEASE_PERIOD), window) {
            emptyList()
        } shouldBe false
    }

    @Test
    fun `a novel due inside the window is fetched`() = runTest {
        novelPassesSmartUpdate(Novel.create().copy(nextUpdate = 1_500L), setOf(MANGA_OUTSIDE_RELEASE_PERIOD), window) {
            emptyList()
        } shouldBe true
    }

    @Test
    fun `a novel set to fetch once is skipped once it has chapters, whatever the restrictions`() = runTest {
        val novel = Novel.create().copy(updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE)

        novelPassesSmartUpdate(novel, emptySet(), window) { listOf(chapter(read = false)) } shouldBe false
    }

    @Test
    fun `a novel set to fetch once without chapters is fetched`() = runTest {
        val novel = Novel.create().copy(updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE)

        novelPassesSmartUpdate(novel, emptySet(), window) { emptyList() } shouldBe true
    }

    @Test
    fun `a novel with no chapters yet is not skipped as unstarted`() = runTest {
        novelPassesSmartUpdate(Novel.create(), setOf(MANGA_NON_READ), window) { emptyList() } shouldBe true
    }

    private fun chapter(read: Boolean) = NovelChapter(
        id = 1L,
        novelId = 1L,
        url = "/c1",
        name = "Chapter 1",
        read = read,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = 1.0,
        sourceOrder = 0L,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )
}
