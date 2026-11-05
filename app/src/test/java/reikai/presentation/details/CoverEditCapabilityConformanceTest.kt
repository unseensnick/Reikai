package reikai.presentation.details

import android.content.Context
import android.net.Uri
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkStatic
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
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.novel.local.LocalNovelSource
import reikai.presentation.novel.details.NovelCoverViewModel
import reikai.util.mockIoDispatcher
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.source.local.LocalSource
import java.io.ByteArrayInputStream
import kotlin.time.Instant

/**
 * A custom cover is kept only for an entry in the library (removal deletes it), so the cover viewer
 * offers Edit only there. A local entry of either type takes one anywhere: it is written into the
 * series' own folder, which outlives the library.
 */
class CoverEditCapabilityConformanceTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Dispatchers::class)
        MangaCover.vibrantCoverColorMap.clear()
    }

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a local entry outside the library can take a custom cover`(probe: CoverEditProbe) = runTest {
        editable(probe.model(inLibrary = false, local = true)) shouldBe true
    }

    /** Manga's write is upstream's `Manga.editCover`; the novel twin writes through its local source. */
    @Test
    fun `a local novel's new cover is written into its series folder`() = runTest {
        mockIoDispatcher(UnconfinedTestDispatcher(testScheduler))
        val probe = NovelCoverEditProbe()
        val model = probe.model(inLibrary = false, local = true)
        editable(model)
        // Relaxed for the snackbar's string, which the write does not depend on.
        val context = mockk<Context>(relaxed = true) {
            every { contentResolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf(1))
        }

        model.editCover(context, mockk<Uri>())
        runCurrent()

        coVerify { probe.localSource.writeCover("/1", any()) }
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaCoverEditProbe(), NovelCoverEditProbe())
    }
}

/** One content type's cover model over a single stored entry. */
interface CoverEditProbe {
    val id: EntryId

    fun model(inLibrary: Boolean, local: Boolean = false): EntryCoverViewModel<*>
}

// Read while subscribed, since the answer is shared only while the dialog collects it.
private fun TestScope.editable(model: EntryCoverViewModel<*>): Boolean {
    backgroundScope.launch { model.isCoverEditable.collect {} }
    runCurrent()
    return model.isCoverEditable.value
}

class MangaCoverEditProbe : CoverEditProbe {
    override val id = EntryId.Manga(1L)

    override fun toString() = "manga"

    override fun model(inLibrary: Boolean, local: Boolean): EntryCoverViewModel<*> {
        val manga = Manga.create().copy(
            id = 1L,
            source = if (local) LocalSource.ID else 1L,
            favoriteAt = Instant.fromEpochMilliseconds(100L).takeIf {
                inLibrary
            },
        )
        return MangaEntryCoverViewModel(
            mangaId = manga.id,
            getManga = mockk<GetManga> { every { subscribe(manga.id) } returns flowOf(manga) },
            getCustomMangaInfo = mockk<GetCustomMangaInfo> { every { subscribe(manga.id) } returns flowOf(null) },
            coverCache = mockk(relaxed = true),
            updateManga = mockk(relaxed = true),
            coverManager = mockk(relaxed = true),
            clearCustomCover = mockk(relaxed = true),
            imageSaver = mockk(relaxed = true),
        )
    }
}

class NovelCoverEditProbe : CoverEditProbe {
    override val id = EntryId.Novel(1L)

    val localSource = mockk<LocalNovelSource> { coEvery { writeCover(any(), any()) } returns "content://cover" }

    override fun toString() = "novel"

    override fun model(inLibrary: Boolean, local: Boolean): EntryCoverViewModel<*> {
        val novel = Novel.create().copy(
            id = 1L,
            source = if (local) LocalNovelSource.ID else "plugin",
            url = "/1",
            favoriteAt = Instant.fromEpochMilliseconds(100L).takeIf {
                inLibrary
            },
        )
        return NovelCoverViewModel(
            novelUrl = novel.url,
            novelSource = novel.source,
            novelRepo = mockk<NovelRepository>(relaxed = true) {
                every { getByUrlAndSourceAsFlow(novel.url, novel.source) } returns flowOf(novel)
            },
            getCustomNovelInfo = mockk<GetCustomNovelInfo> { every { subscribe(novel.id) } returns flowOf(null) },
            updateNovel = mockk(relaxed = true),
            coverCache = mockk(relaxed = true),
            clearCustomCover = mockk(relaxed = true),
            localNovelSource = localSource,
            imageSaver = mockk(relaxed = true),
        )
    }
}
