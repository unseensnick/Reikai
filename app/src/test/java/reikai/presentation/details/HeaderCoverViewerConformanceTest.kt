package reikai.presentation.details

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.presentation.novel.details.NovelCoverViewModel
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga

/**
 * On a merged series' source chip, the header and the cover viewer draw the same cover: the chip's own,
 * with the cover address of its own custom-info row.
 */
class HeaderCoverViewerConformanceTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a chip with its own custom cover address shows it in the header and the viewer alike`(
        probe: HeaderCoverProbe,
    ) = runTest {
        val covers = probe.covers(siblingCoverUrl = "sibling-custom")
        covers.header shouldBe viewed(covers.viewer)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a chip with no custom cover address shows its source's in the header and the viewer alike`(
        probe: HeaderCoverProbe,
    ) = runTest {
        val covers = probe.covers(siblingCoverUrl = null)
        covers.header shouldBe viewed(covers.viewer)
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaHeaderCoverProbe(), NovelHeaderCoverProbe())
    }
}

/** The header's cover address and the viewer built for the same chip, as the adapters build them. */
class HeaderCovers(val header: String?, val viewer: EntryCoverViewModel<*>)

/** One content type's merged page: an anchor with its own custom cover, and a sibling chip in view. */
interface HeaderCoverProbe {
    fun covers(siblingCoverUrl: String?): HeaderCovers
}

private fun TestScope.viewed(model: EntryCoverViewModel<*>): String? {
    backgroundScope.launch { model.entry.collect {} }
    runCurrent()
    return when (val entry = model.entry.value) {
        is Manga -> entry.thumbnailUrl
        is Novel -> entry.thumbnailUrl
        else -> error("no entry")
    }
}

class MangaHeaderCoverProbe : HeaderCoverProbe {
    override fun toString() = "manga"

    override fun covers(siblingCoverUrl: String?): HeaderCovers {
        val anchor = Manga.create().copy(id = 1L, thumbnailUrl = "anchor")
        val sibling = Manga.create().copy(id = 2L, thumbnailUrl = "sibling")
        val anchorInfo = CustomMangaInfo(mangaId = 1L, title = "Custom", thumbnailUrl = "anchor-custom")
        val siblingInfo = siblingCoverUrl?.let { CustomMangaInfo(mangaId = 2L, thumbnailUrl = it) }
        val viewer = MangaEntryCoverViewModel(
            mangaId = sibling.id,
            getManga = mockk<GetManga> { every { subscribe(sibling.id) } returns flowOf(sibling) },
            getCustomMangaInfo = mockk<GetCustomMangaInfo> {
                every { subscribe(sibling.id) } returns flowOf(siblingInfo)
            },
            coverCache = mockk(relaxed = true),
            updateManga = mockk(relaxed = true),
            coverManager = mockk(relaxed = true),
            clearCustomCover = mockk(relaxed = true),
            imageSaver = mockk(relaxed = true),
        )
        return HeaderCovers(shownManga(anchor, sibling, anchorInfo, siblingInfo).thumbnailUrl, viewer)
    }
}

class NovelHeaderCoverProbe : HeaderCoverProbe {
    override fun toString() = "novel"

    override fun covers(siblingCoverUrl: String?): HeaderCovers {
        val anchor = Novel.create().copy(id = 1L, source = "a", url = "/1", thumbnailUrl = "anchor")
        val sibling = Novel.create().copy(id = 2L, source = "b", url = "/2", thumbnailUrl = "sibling")
        val anchorInfo = CustomNovelInfo(novelId = 1L, title = "Custom", thumbnailUrl = "anchor-custom")
        val siblingInfo = siblingCoverUrl?.let { CustomNovelInfo(novelId = 2L, thumbnailUrl = it) }
        val viewer = NovelCoverViewModel(
            novelUrl = sibling.url,
            novelSource = sibling.source,
            novelRepo = mockk<NovelRepository> {
                every { getByUrlAndSourceAsFlow(sibling.url, sibling.source) } returns flowOf(sibling)
            },
            getCustomNovelInfo = mockk<GetCustomNovelInfo> {
                every { subscribe(sibling.id) } returns flowOf(siblingInfo)
            },
            updateNovel = mockk(relaxed = true),
            coverCache = mockk(relaxed = true),
            clearCustomCover = mockk(relaxed = true),
            imageSaver = mockk(relaxed = true),
        )
        return HeaderCovers(shownNovel(anchor, sibling, anchorInfo, siblingInfo).thumbnailUrl, viewer)
    }
}
