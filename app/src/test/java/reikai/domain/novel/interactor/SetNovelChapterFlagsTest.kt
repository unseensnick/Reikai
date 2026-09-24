package reikai.domain.novel.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.effectiveHideChapterTitles
import reikai.domain.novel.model.effectiveSortDescending
import reikai.domain.novel.model.effectiveSorting
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * Title display and chapter sort each have their own local-override bit, so changing one on a novel
 * leaves the other following the global default. They used to share the sort bit, and hiding titles
 * switched a never-sorted novel to source order, descending.
 */
class SetNovelChapterFlagsTest {

    // Seeded through the constructor: InMemoryPreferenceStore never reflects a set() on the next read.
    private val prefs = NovelPreferences(
        InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference(
                    "ln_default_chapter_sort",
                    NovelChapterFlags.SORTING_NUMBER,
                    0L,
                ),
                InMemoryPreferenceStore.InMemoryPreference("ln_default_chapter_sort_desc", false, true),
                InMemoryPreferenceStore.InMemoryPreference("ln_default_chapter_hide_titles", true, false),
            ),
        ),
    )

    private suspend fun written(write: suspend SetNovelChapterFlags.(Novel) -> Unit): Novel {
        val sent = slot<NovelUpdate>()
        val repository = mockk<NovelRepository> { coEvery { update(capture(sent)) } returns true }
        val novel = Novel.create().copy(id = 1L, chapterFlags = 0L)
        SetNovelChapterFlags(repository).write(novel)
        return novel.copy(chapterFlags = sent.captured.chapterFlags!!)
    }

    @Test
    fun `changing title display leaves the sort on the global default`() = runTest {
        val novel = written { awaitSetHideTitles(it, hide = false) }
        (novel.effectiveSorting(prefs) to novel.effectiveSortDescending(prefs)) shouldBe
            (NovelChapterFlags.SORTING_NUMBER to false)
    }

    @Test
    fun `changing the sort leaves title display on the global default`() = runTest {
        val novel = written { awaitSetSortOrder(it, NovelChapterFlags.SORTING_ALPHABET, descending = true) }
        novel.effectiveHideChapterTitles(prefs) shouldBe true
    }

    @Test
    fun `a title display change sticks`() = runTest {
        val novel = written { awaitSetHideTitles(it, hide = false) }
        novel.effectiveHideChapterTitles(prefs) shouldBe false
    }

    @Test
    fun `clearing the local overrides returns title display to the global default`() = runTest {
        val displayed = Novel.create().copy(
            id = 1L,
            chapterFlags = NovelChapterFlags.DISPLAY_LOCAL or NovelChapterFlags.DISPLAY_NAME,
        )
        val sent = slot<NovelUpdate>()
        val repository = mockk<NovelRepository> { coEvery { update(capture(sent)) } returns true }
        SetNovelChapterFlags(repository).awaitClearLocalOverrides(displayed)
        displayed.copy(chapterFlags = sent.captured.chapterFlags!!).effectiveHideChapterTitles(prefs) shouldBe true
    }
}
