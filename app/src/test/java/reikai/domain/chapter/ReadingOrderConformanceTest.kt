package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.sortedAndFiltered
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga

/** One entry's chapters as its source listed them, with every sort axis disagreeing with every other. */
data class ListedChapter(val id: Long, val name: String, val number: Double, val upload: Long)

/**
 * One content type's answers to the reading-order questions: it builds the list its details screen shows
 * from its own comparator, then asks the shared [ReadingOrder] the same questions the other type does.
 */
class ReadingOrderCase<T>(
    private val label: String,
    private val shown: (sorting: Long, descending: Boolean, read: Set<Long>) -> List<T>,
    private val id: (T) -> Long,
    private val isRead: (T) -> Boolean,
) {
    fun readingOrder(sorting: Long, descending: Boolean = false): List<Long> =
        ReadingOrder.of(shown(sorting, descending, emptySet()), descending).map(id)

    fun resumeTarget(sorting: Long, read: Set<Long>, descending: Boolean = false): Long? =
        ReadingOrder.nextToRead(ReadingOrder.of(shown(sorting, descending, read), descending), isRead)?.let(id)

    fun markedPrevious(sorting: Long, pointer: Long, descending: Boolean = false): List<Long> =
        ReadingOrder.before(ReadingOrder.of(shown(sorting, descending, emptySet()), descending)) {
            id(it) == pointer
        }.map(id)

    override fun toString() = label
}

/**
 * The reading-order rule over both content types. Resume, "download next N" and "mark previous as read"
 * all ask [ReadingOrder] the same two questions on both sides, and each type supplies only the comparator
 * that orders its own list. What this pins is that those comparators agree: the novel sites used to walk
 * raw source order, so a novel sorted by name resumed at a chapter its reader reaches much later.
 */
class ReadingOrderConformanceTest {

    @ParameterizedTest(name = "{0} sorted {1}")
    @MethodSource("readingOrders")
    fun `the reader walks the order the chapter list is sorted in`(
        case: ReadingOrderCase<*>,
        mode: String,
        sorting: Long,
        expected: List<Long>,
    ) {
        case.readingOrder(sorting) shouldBe expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `resume skips past a chapter read out of order`(case: ReadingOrderCase<*>) {
        // Alphabetically the order is Alpha, Beta, Gamma; with Alpha and Gamma read, Beta is next.
        case.resumeTarget(SORTING_ALPHABET, read = setOf(2L, 3L)) shouldBe 1L
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `showing the list newest first does not change which chapter is next`(case: ReadingOrderCase<*>) {
        case.resumeTarget(SORTING_NUMBER, read = setOf(2L), descending = true) shouldBe 3L
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `mark previous covers every chapter the reader passes first`(case: ReadingOrderCase<*>) {
        case.markedPrevious(SORTING_ALPHABET, pointer = 3L) shouldBe listOf(2L, 1L)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `mark previous on a newest-first list covers the same chapters`(case: ReadingOrderCase<*>) {
        case.markedPrevious(SORTING_ALPHABET, pointer = 3L, descending = true) shouldBe listOf(2L, 1L)
    }

    @Test
    fun `the two flag layouts agree on the sort bits, which is what lets one table cover both`() {
        listOf(SORTING_SOURCE, SORTING_NUMBER, SORTING_UPLOAD_DATE, SORTING_ALPHABET) shouldBe
            listOf(
                Manga.CHAPTER_SORTING_SOURCE,
                Manga.CHAPTER_SORTING_NUMBER,
                Manga.CHAPTER_SORTING_UPLOAD_DATE,
                Manga.CHAPTER_SORTING_ALPHABET,
            )
    }

    companion object {

        private const val SORTING_SOURCE = NovelChapterFlags.SORTING_SOURCE
        private const val SORTING_NUMBER = NovelChapterFlags.SORTING_NUMBER
        private const val SORTING_UPLOAD_DATE = NovelChapterFlags.SORTING_UPLOAD_DATE
        private const val SORTING_ALPHABET = NovelChapterFlags.SORTING_ALPHABET

        private val listed = listOf(
            ListedChapter(id = 1L, name = "Beta", number = 3.0, upload = 100L),
            ListedChapter(id = 2L, name = "Alpha", number = 1.0, upload = 300L),
            ListedChapter(id = 3L, name = "Gamma", number = 2.0, upload = 200L),
        )

        private val mangaCase = ReadingOrderCase<Chapter>(
            label = "manga",
            shown = { sorting, descending, read ->
                val manga = Manga.create().copy(
                    chapterFlags = sorting or
                        if (descending) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC,
                )
                listed.mapIndexed { index, spec ->
                    Chapter.create().copy(
                        id = spec.id,
                        name = spec.name,
                        chapterNumber = spec.number,
                        dateUpload = spec.upload,
                        // A manga source lists newest first, so the chapter listed last is read first.
                        sourceOrder = (listed.lastIndex - index).toLong(),
                        read = spec.id in read,
                    )
                }.sortedWith(getChapterSort(manga))
            },
            id = { it.id },
            isRead = { it.read },
        )

        private val novelCase = ReadingOrderCase<NovelChapter>(
            label = "novels",
            shown = { sorting, descending, read ->
                // Its own sort and filter, so the global defaults never enter the comparison.
                val novel = Novel.create().copy(
                    chapterFlags = NovelChapterFlags.SORT_LOCAL or NovelChapterFlags.FILTER_LOCAL or
                        sorting or
                        if (descending) NovelChapterFlags.SORT_DESC else NovelChapterFlags.SORT_ASC,
                )
                listed.mapIndexed { index, spec ->
                    NovelChapter(
                        id = spec.id,
                        novelId = 1L,
                        url = "u${spec.id}",
                        name = spec.name,
                        read = spec.id in read,
                        bookmark = false,
                        lastTextProgress = 0L,
                        chapterNumber = spec.number,
                        // A novel source lists oldest first, the opposite of a manga source.
                        sourceOrder = index.toLong(),
                        dateFetch = 0L,
                        dateUpload = spec.upload,
                        page = "",
                    )
                }.sortedAndFiltered(
                    novel,
                    NovelPreferences(InMemoryPreferenceStore(sequenceOf())),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                )
            },
            id = { it.id },
            isRead = { it.read },
        )

        @JvmStatic
        fun cases(): List<ReadingOrderCase<*>> = listOf(mangaCase, novelCase)

        @JvmStatic
        fun readingOrders(): List<Arguments> = cases().flatMap { case ->
            listOf(
                Arguments.of(case, "by source", SORTING_SOURCE, listOf(1L, 2L, 3L)),
                Arguments.of(case, "by number", SORTING_NUMBER, listOf(2L, 3L, 1L)),
                Arguments.of(case, "by upload date", SORTING_UPLOAD_DATE, listOf(1L, 3L, 2L)),
                Arguments.of(case, "alphabetically", SORTING_ALPHABET, listOf(2L, 1L, 3L)),
            )
        }
    }
}
