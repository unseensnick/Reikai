package reikai.domain.novel.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * The order the novel reader pages in. It used to be hardcoded to chapter number, so a novel sorted by
 * upload date or alphabetically on its own chapter list was read in a different order than it was shown
 * in. The comparator is always ascending: reading runs first to last whichever way the list is displayed,
 * which is the same call the manga reader makes.
 */
class NovelReadingOrderTest {

    // Seeded rather than set(): InMemoryPreferenceStore holds an immutable map and hands back a fresh
    // Preference each call, so a set() never reaches the next read.
    private fun prefs(globalSort: Long? = null) = NovelPreferences(
        InMemoryPreferenceStore(
            globalSort?.let {
                sequenceOf(InMemoryPreferenceStore.InMemoryPreference("ln_default_chapter_sort", it, 0L))
            } ?: sequenceOf(),
        ),
    )

    private val prefs = prefs()

    private fun novel(sorting: Long, descending: Boolean = false) = Novel.create().copy(
        chapterFlags = NovelChapterFlags.SORT_LOCAL or
            sorting or
            if (descending) NovelChapterFlags.SORT_ASC else NovelChapterFlags.SORT_DESC,
    )

    private fun chapter(id: Long, name: String, number: Double, order: Long, upload: Long) = NovelChapter(
        id = id,
        novelId = 1L,
        url = "u$id",
        name = name,
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = number,
        sourceOrder = order,
        dateFetch = 0L,
        dateUpload = upload,
        page = "",
    )

    // Every axis disagrees with every other, so each mode yields a distinct order and no test can pass
    // by accident on a comparator meant for a different field. An earlier fixture had upload date and
    // chapter number agreeing, and a mutation swapping the two comparators went undetected.
    private val beta = chapter(id = 1, name = "Beta", number = 3.0, order = 2, upload = 100)
    private val alpha = chapter(id = 2, name = "Alpha", number = 1.0, order = 3, upload = 300)
    private val gamma = chapter(id = 3, name = "Gamma", number = 2.0, order = 1, upload = 200)
    private val all = listOf(beta, alpha, gamma)

    private fun orderedIds(sorting: Long, descending: Boolean = false) =
        all.sortedWith(readingOrderComparator(novel(sorting, descending), prefs)).map { it.id }

    @Test
    fun `source order is the default and follows the source's own numbering`() {
        orderedIds(NovelChapterFlags.SORTING_SOURCE) shouldBe listOf(3L, 1L, 2L)
    }

    @Test
    fun `sorting by number reads in chapter-number order`() {
        orderedIds(NovelChapterFlags.SORTING_NUMBER) shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `sorting by upload date reads oldest first`() {
        orderedIds(NovelChapterFlags.SORTING_UPLOAD_DATE) shouldBe listOf(1L, 3L, 2L)
    }

    @Test
    fun `sorting alphabetically reads by chapter name`() {
        orderedIds(NovelChapterFlags.SORTING_ALPHABET) shouldBe listOf(2L, 1L, 3L)
    }

    @Test
    fun `a descending chapter list is still read ascending`() {
        // The direction bit belongs to how the list is shown, not to which chapter comes next.
        orderedIds(NovelChapterFlags.SORTING_NUMBER, descending = true) shouldBe
            orderedIds(NovelChapterFlags.SORTING_NUMBER, descending = false)
    }

    @Test
    fun `a novel without its own sort falls back to the global default`() {
        val global = prefs(globalSort = NovelChapterFlags.SORTING_ALPHABET)
        all.sortedWith(readingOrderComparator(Novel.create(), global)).map { it.id } shouldBe
            listOf(2L, 1L, 3L)
    }
}
