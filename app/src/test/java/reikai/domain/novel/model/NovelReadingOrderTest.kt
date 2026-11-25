package reikai.domain.novel.model

import io.kotest.matchers.shouldBe
import mihon.core.common.extensions.toInstantOrNull
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import kotlin.time.Instant

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

    private fun novel(sorting: Long) = Novel.create().copy(
        chapterFlags = NovelChapterFlags.SORT_LOCAL or sorting or NovelChapterFlags.SORT_ASC,
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
        dateFetch = Instant.fromEpochMilliseconds(0L),
        dateUpload = upload.toInstantOrNull(),
        page = "",
    )

    // Every axis disagrees with every other, so each mode yields a distinct order and no test can pass
    // by accident on a comparator meant for a different field. An earlier fixture had upload date and
    // chapter number agreeing, and a mutation swapping the two comparators went undetected.
    private val beta = chapter(id = 1, name = "Beta", number = 3.0, order = 2, upload = 100)
    private val alpha = chapter(id = 2, name = "Alpha", number = 1.0, order = 3, upload = 300)
    private val gamma = chapter(id = 3, name = "Gamma", number = 2.0, order = 1, upload = 200)
    private val all = listOf(beta, alpha, gamma)

    @Test
    fun `a novel without its own sort falls back to the global default`() {
        val global = prefs(globalSort = NovelChapterFlags.SORTING_ALPHABET)
        all.sortedWith(readingOrderComparator(Novel.create(), global)).map { it.id } shouldBe
            listOf(2L, 1L, 3L)
    }

    // Two chapters tying on every sort key, handed over against source order, which is how the reader
    // hands a chapter it re-added to the end of its list. Only the source-order tiebreak orders them.
    private val tiedFirst = chapter(id = 4, name = "Same", number = 5.0, order = 1, upload = 500)
    private val tiedSecond = chapter(id = 5, name = "Same", number = 5.0, order = 2, upload = 500)

    private fun tiedIds(sorting: Long) =
        listOf(tiedSecond, tiedFirst).sortedWith(readingOrderComparator(novel(sorting), prefs)).map { it.id }

    @Test
    fun `chapters sharing a number read in source order`() {
        tiedIds(NovelChapterFlags.SORTING_NUMBER) shouldBe listOf(4L, 5L)
    }

    @Test
    fun `chapters sharing an upload date read in source order`() {
        tiedIds(NovelChapterFlags.SORTING_UPLOAD_DATE) shouldBe listOf(4L, 5L)
    }

    @Test
    fun `chapters sharing a name read in source order`() {
        tiedIds(NovelChapterFlags.SORTING_ALPHABET) shouldBe listOf(4L, 5L)
    }
}
