package reikai.domain.track.source

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceTracker
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.source.NovelSource
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * What a source's tracker is handed, pinned once for both content types: each probe reads one type's
 * entry out of its own tables, and the cases are shared, so neither can hand the site something else.
 */
class SourceTrackedEntriesConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the uncategorized category is not passed to the site`(probe: LoaderProbe) = runTest {
        val categories = listOf(Category(Category.UNCATEGORIZED_ID, "Default", 0, 0), Category(5, "Reading", 1, 0))

        probe.loader(categories = categories).load(probe.entry)!!.categories shouldBe listOf("Reading")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `each chapter keeps its id, read state and number`(probe: LoaderProbe) = runTest {
        val chapters = listOf(ChapterRow(1L, read = true, 1.0), ChapterRow(2L, read = false, 2.5))

        probe.loader(chapters = chapters).load(probe.entry)!!.chapters
            .map { ChapterRow(it.id, it.read, it.number) } shouldBe chapters
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `whether the entry is in the library is read from its row`(probe: LoaderProbe) = runTest {
        probe.loader(favorite = true).load(probe.entry)!!.favorite shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry whose source does not track loads as nothing`(probe: LoaderProbe) = runTest {
        probe.loader(tracking = false).load(probe.entry).shouldBeNull()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry that is gone loads as nothing`(probe: LoaderProbe) = runTest {
        probe.loader(exists = false).load(probe.entry).shouldBeNull()
    }

    data class ChapterRow(val id: Long, val read: Boolean, val number: Double)

    interface LoaderProbe {
        val entry: EntryId

        fun loader(
            tracking: Boolean = true,
            exists: Boolean = true,
            favorite: Boolean = false,
            chapters: List<ChapterRow> = emptyList(),
            categories: List<Category> = emptyList(),
        ): TrackedEntryLoader
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }
}

private val siteTracker = object : SourceTracker {}

class MangaProbe : SourceTrackedEntriesConformanceTest.LoaderProbe {

    override val entry = EntryId.Manga(1)

    override fun toString() = "manga"

    override fun loader(
        tracking: Boolean,
        exists: Boolean,
        favorite: Boolean,
        chapters: List<SourceTrackedEntriesConformanceTest.ChapterRow>,
        categories: List<Category>,
    ): TrackedEntryLoader {
        val source = if (tracking) {
            mockk<Source>(moreInterfaces = arrayOf(SourceTracker::class), relaxed = true)
        } else {
            mockk<Source>(relaxed = true)
        }
        every { source.name } returns "Site"
        val sourceManager = mockk<tachiyomi.domain.source.service.SourceManager>()
        coEvery { sourceManager.get(any()) } returns source
        return SourceTrackedEntries(
            mangaRepository = mockk {
                if (exists) {
                    coEvery { getMangaById(1) } returns
                        Manga.create().copy(id = 1, source = 7, url = "/1", favorite = favorite)
                } else {
                    coEvery { getMangaById(1) } throws IllegalStateException("gone")
                }
            },
            chapterRepository = mockk {
                coEvery { getChapterByMangaId(1, any()) } returns chapters.map {
                    Chapter.create().copy(id = it.id, mangaId = 1, read = it.read, chapterNumber = it.number)
                }
            },
            getCategories = mockk { coEvery { await(1L) } returns categories },
            sourceManager = sourceManager,
            novelRepository = mockk(),
            novelChapterRepository = mockk(),
            getNovelCategories = mockk(),
            novelSourceManager = mockk(),
        )
    }
}

class NovelProbe : SourceTrackedEntriesConformanceTest.LoaderProbe {

    override val entry = EntryId.Novel(1)

    override fun toString() = "novel"

    override fun loader(
        tracking: Boolean,
        exists: Boolean,
        favorite: Boolean,
        chapters: List<SourceTrackedEntriesConformanceTest.ChapterRow>,
        categories: List<Category>,
    ): TrackedEntryLoader {
        val source = mockk<NovelSource> {
            every { name } returns "Site"
            every { tracker } returns if (tracking) siteTracker else null
        }
        return SourceTrackedEntries(
            mangaRepository = mockk(),
            chapterRepository = mockk(),
            getCategories = mockk(),
            sourceManager = mockk(),
            novelRepository = mockk {
                coEvery { getById(1) } returns
                    if (exists) Novel.create().copy(id = 1, source = "s", url = "/1", favorite = favorite) else null
            },
            novelChapterRepository = mockk {
                coEvery { getByNovelId(1) } returns chapters.map {
                    NovelChapter(
                        id = it.id,
                        novelId = 1L,
                        url = "u${it.id}",
                        name = "Chapter ${it.number}",
                        read = it.read,
                        bookmark = false,
                        lastTextProgress = 0,
                        chapterNumber = it.number,
                        sourceOrder = it.id,
                        dateFetch = 0,
                        dateUpload = 0,
                        page = "",
                    )
                }
            },
            getNovelCategories = mockk { coEvery { awaitByNovelId(1L) } returns categories },
            novelSourceManager = mockk { coEvery { getWithoutPlugins("s") } returns source },
        )
    }
}
