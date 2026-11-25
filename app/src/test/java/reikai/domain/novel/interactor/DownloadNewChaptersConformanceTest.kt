package reikai.domain.novel.interactor

import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.category.GetNovelCategories
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

/** The "download new chapters" rule, run over the manga filter and its novel twin. */
class DownloadNewChaptersConformanceTest {

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `with the setting off nothing is downloaded`(side: Side) = runTest {
        val filter = side.filter(enabled = false)

        filter.newNumbers(favorite = true, new = listOf(1.0)).shouldContainExactly()
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `a new chapter of a favourite is downloaded`(side: Side) = runTest {
        val filter = side.filter(enabled = true)

        filter.newNumbers(favorite = true, new = listOf(1.0)) shouldContainExactly listOf(1.0)
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `an entry outside the library downloads nothing`(side: Side) = runTest {
        val filter = side.filter(enabled = true)

        filter.newNumbers(favorite = false, new = listOf(1.0)).shouldContainExactly()
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `an entry in an excluded category downloads nothing`(side: Side) = runTest {
        val filter = side.filter(enabled = true, excluded = setOf(CATEGORY))

        filter.newNumbers(favorite = true, new = listOf(1.0)).shouldContainExactly()
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `an entry in an included category downloads`(side: Side) = runTest {
        val filter = side.filter(enabled = true, included = setOf(CATEGORY))

        filter.newNumbers(favorite = true, new = listOf(1.0)) shouldContainExactly listOf(1.0)
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `an entry in both an included and an excluded category downloads nothing`(side: Side) = runTest {
        val filter = side.filter(enabled = true, included = setOf(CATEGORY), excluded = setOf(CATEGORY))

        filter.newNumbers(favorite = true, new = listOf(1.0)).shouldContainExactly()
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `an entry outside every included category downloads nothing`(side: Side) = runTest {
        val filter = side.filter(enabled = true, included = setOf(CATEGORY + 1))

        filter.newNumbers(favorite = true, new = listOf(1.0)).shouldContainExactly()
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `an uncategorized entry counts as Default, so excluding Default downloads nothing`(side: Side) = runTest {
        val filter = side.filter(enabled = true, excluded = setOf(DEFAULT), categorized = false)

        filter.newNumbers(favorite = true, new = listOf(1.0)).shouldContainExactly()
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `an uncategorized entry counts as Default, so including Default downloads`(side: Side) = runTest {
        val filter = side.filter(enabled = true, included = setOf(DEFAULT), categorized = false)

        filter.newNumbers(favorite = true, new = listOf(1.0)) shouldContainExactly listOf(1.0)
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `with unread only on a chapter numbered like a read one is skipped`(side: Side) = runTest {
        val filter = side.filter(enabled = true, unreadOnly = true, read = listOf(1.0))

        filter.newNumbers(favorite = true, new = listOf(1.0, 2.0)) shouldContainExactly listOf(2.0)
    }

    // An unrecognised number names no chapter, so a read one numbered -1 skips no new one numbered -1.
    @ParameterizedTest
    @EnumSource(Side::class)
    fun `with unread only an unrecognised number skips nothing`(side: Side) = runTest {
        val filter = side.filter(enabled = true, unreadOnly = true, read = listOf(-1.0))

        filter.newNumbers(favorite = true, new = listOf(-1.0)) shouldContainExactly listOf(-1.0)
    }

    fun interface Filter {
        suspend fun newNumbers(favorite: Boolean, new: List<Double>): List<Double>
    }

    enum class Side {
        MANGA {
            override fun filter(
                enabled: Boolean,
                included: Set<Long>,
                excluded: Set<Long>,
                unreadOnly: Boolean,
                read: List<Double>,
                categorized: Boolean,
            ): Filter {
                val prefs = DownloadPreferences(EmittingPreferenceStore())
                prefs.downloadNewChapters.set(enabled)
                prefs.downloadNewChapterCategories.set(included.map { it.toString() }.toSet())
                prefs.downloadNewChapterCategoriesExclude.set(excluded.map { it.toString() }.toSet())
                prefs.downloadNewUnreadChaptersOnly.set(unreadOnly)
                val chapters = mockk<GetChaptersByMangaId> {
                    coEvery { await(any(), any()) } returns
                        read.map { Chapter.create().copy(read = true, chapterNumber = it) }
                }
                val categories =
                    mockk<GetCategories> { coEvery { await(any<Long>()) } returns categoriesOf(categorized) }
                val filter = FilterChaptersForDownload(chapters, prefs, categories)
                return Filter { favorite, new ->
                    filter.await(
                        Manga.create().copy(id = 1, favoriteAt = Instant.fromEpochMilliseconds(0L).takeIf { favorite }),
                        new.map { Chapter.create().copy(chapterNumber = it) },
                    ).map { it.chapterNumber }
                }
            }
        },
        NOVELS {
            override fun filter(
                enabled: Boolean,
                included: Set<Long>,
                excluded: Set<Long>,
                unreadOnly: Boolean,
                read: List<Double>,
                categorized: Boolean,
            ): Filter {
                val prefs = NovelPreferences(EmittingPreferenceStore())
                prefs.downloadNewChapters().set(enabled)
                prefs.downloadNewChapterCategories().set(included.map { it.toString() }.toSet())
                prefs.downloadNewChapterCategoriesExclude().set(excluded.map { it.toString() }.toSet())
                prefs.downloadNewUnreadChaptersOnly().set(unreadOnly)
                val chapters = mockk<NovelChapterRepository> {
                    coEvery { getByNovelId(any()) } returns read.map { chapter(it).copy(read = true) }
                }
                val categories = mockk<GetNovelCategories> {
                    coEvery { awaitByNovelId(any()) } returns categoriesOf(categorized)
                }
                val filter = FilterNovelChaptersForDownload(chapters, prefs, categories)
                return Filter { favorite, new ->
                    filter.await(
                        Novel.create().copy(
                            id = 1,
                            favoriteAt = Instant.fromEpochMilliseconds(0L).takeIf {
                                favorite
                            },
                        ),
                        new.map(::chapter),
                    )
                        .map { it.chapterNumber }
                }
            }
        },
        ;

        abstract fun filter(
            enabled: Boolean,
            included: Set<Long> = emptySet(),
            excluded: Set<Long> = emptySet(),
            unreadOnly: Boolean = false,
            read: List<Double> = emptyList(),
            categorized: Boolean = true,
        ): Filter
    }

    private companion object {
        const val CATEGORY = 5L

        // What both filters file an entry with no category under.
        const val DEFAULT = 0L

        fun categoriesOf(categorized: Boolean) =
            if (categorized) listOf(Category(id = CATEGORY, name = "", order = 0, flags = 0)) else emptyList()

        fun chapter(number: Double) = NovelChapter(
            id = number.toLong(), novelId = 1L, url = "", name = "", read = false, bookmark = false,
            lastTextProgress = 0L, chapterNumber = number, sourceOrder = 0L,
            dateFetch = Instant.fromEpochMilliseconds(
                0L,
            ),
            dateUpload = null, page = "",
        )
    }
}
