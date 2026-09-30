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
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.presentation.novel.details.NovelCoverViewModel
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource

/**
 * A custom cover is kept only for an entry in the library (removal deletes it), so the cover viewer
 * offers Edit only there. Manga writes one for a local-source entry too, which novels have no kind of.
 */
class CoverEditCapabilityConformanceTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry outside the library cannot take a custom cover`(probe: CoverEditProbe) = runTest {
        editable(probe.model(inLibrary = false)) shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry in the library can take a custom cover`(probe: CoverEditProbe) = runTest {
        editable(probe.model(inLibrary = true)) shouldBe true
    }

    @Test
    fun `a local manga outside the library can take a custom cover`() = runTest {
        editable(MangaCoverEditProbe(source = LocalSource.ID).model(inLibrary = false)) shouldBe true
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaCoverEditProbe(), NovelCoverEditProbe())
    }
}

/** One content type's cover model over a single stored entry. */
interface CoverEditProbe {
    fun model(inLibrary: Boolean): EntryCoverViewModel<*>
}

// Read while subscribed, since the answer is shared only while the dialog collects it.
private fun TestScope.editable(model: EntryCoverViewModel<*>): Boolean {
    backgroundScope.launch { model.isCoverEditable.collect {} }
    runCurrent()
    return model.isCoverEditable.value
}

class MangaCoverEditProbe(private val source: Long = 1L) : CoverEditProbe {
    override fun toString() = "manga"

    override fun model(inLibrary: Boolean): EntryCoverViewModel<*> {
        val manga = Manga.create().copy(id = 1L, source = source, favoriteAt = 100L.takeIf { inLibrary })
        return MangaEntryCoverViewModel(
            mangaId = manga.id,
            getManga = mockk<GetManga> { every { subscribe(manga.id) } returns flowOf(manga) },
            getCustomMangaInfo = mockk<GetCustomMangaInfo> { every { subscribe(manga.id) } returns flowOf(null) },
            coverCache = mockk(relaxed = true),
            updateManga = mockk(relaxed = true),
            coverManager = mockk(relaxed = true),
            imageSaver = mockk(relaxed = true),
        )
    }
}

class NovelCoverEditProbe : CoverEditProbe {
    override fun toString() = "novel"

    override fun model(inLibrary: Boolean): EntryCoverViewModel<*> {
        val novel = Novel.create().copy(id = 1L, source = "plugin", url = "/1", favoriteAt = 100L.takeIf { inLibrary })
        return NovelCoverViewModel(
            novelUrl = novel.url,
            novelSource = novel.source,
            novelRepo = mockk<NovelRepository> {
                every { getByUrlAndSourceAsFlow(novel.url, novel.source) } returns flowOf(novel)
            },
            getCustomNovelInfo = mockk<GetCustomNovelInfo> { every { subscribe(novel.id) } returns flowOf(null) },
            updateNovel = mockk(relaxed = true),
            coverCache = mockk(relaxed = true),
            imageSaver = mockk(relaxed = true),
        )
    }
}
