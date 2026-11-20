package reikai.presentation.notes

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.chapter.LocalVolume
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.chapter.LooseFiles
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.EpubChapterKey
import reikai.presentation.details.VolumeLabel
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import kotlin.time.Instant

/** What a volume's notes page opens on, read from its series rather than carried by the screen, for both types. */
class VolumeNotesLoadTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a volume no longer in its series closes its notes`(type: ContentType) {
        val model = model(type, key = "Gone", saved = emptyList())

        model.notes.missing.value shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a volume with no notes written opens on an empty note`(type: ContentType) {
        val model = model(type, key = volumeOf(type), saved = emptyList())

        model.notes.state.value?.notes shouldBe ""
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a volume opens on the notes saved for it`(type: ContentType) {
        val model = model(type, key = volumeOf(type), saved = listOf(LocalVolume(volumeOf(type), null, null, "Mine")))

        model.notes.state.value?.notes shouldBe "Mine"
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `the page heads with the name the volume was given`(type: ContentType) {
        val model = model(type, key = volumeOf(type), saved = listOf(LocalVolume(volumeOf(type), null, "Renamed")))

        model.notes.state.value?.subject?.label shouldBe VolumeLabel.Name("Renamed")
    }

    // Manga only: a novel's every book is a volume, so it has no loose files.
    @Test
    fun `a manga's loose file shown as a volume of its own has its notes page`() {
        val model = model(
            ContentType.MANGA,
            key = LOOSE_FILE,
            saved = emptyList(),
            loose = mapOf(SERIES to LooseFiles.EACH_ITS_OWN),
        )

        model.notes.missing.value shouldBe false
    }

    private fun volumeOf(type: ContentType) = if (type == ContentType.MANGA) MANGA_VOLUME else BOOK

    private fun model(
        type: ContentType,
        key: String,
        saved: List<LocalVolume>,
        loose: Map<String, LooseFiles> = emptyMap(),
    ) = VolumeNotesRoute.Model(
        type,
        SERIES,
        key,
        CoroutineScope(SupervisorJob()),
        mockk<LocalVolumeRepository> {
            coEvery { loadedVolumes(type) } returns mapOf(SERIES to saved)
            coEvery { loadedLooseFiles() } returns loose
        },
        mockk<GetMangaByUrlAndSourceId> {
            coEvery { await(SERIES, LocalSource.ID) } returns
                Manga.create().copy(id = 1L, source = LocalSource.ID, url = SERIES, title = "Series")
        },
        mockk<GetChaptersByMangaId> {
            coEvery { await(1L) } returns listOf(
                Chapter.create().copy(id = 1L, mangaId = 1L, url = "$SERIES/$MANGA_VOLUME/Ch 001.cbz", name = "Ch 001"),
                Chapter.create().copy(id = 2L, mangaId = 1L, url = "$SERIES/$LOOSE_FILE", name = "Ch 010"),
            )
        },
        mockk<NovelRepository> {
            coEvery { getByUrlAndSource(SERIES, LocalNovelSource.ID) } returns
                Novel.create().copy(id = 1L, url = SERIES)
        },
        mockk<NovelChapterRepository> {
            coEvery { getByNovelId(1L) } returns listOf(
                NovelChapter(
                    id = 1L,
                    novelId = 1L,
                    url = LocalNovelSource.chapterPath(SERIES, BOOK, EpubChapterKey("c.xhtml")),
                    name = "$BOOK - c",
                    read = false,
                    bookmark = false,
                    lastTextProgress = 0,
                    chapterNumber = 1.0,
                    sourceOrder = 0,
                    dateFetch = Instant.fromEpochMilliseconds(0),
                    dateUpload = null,
                    page = "",
                ),
            )
        },
    )

    private companion object {
        const val SERIES = "series"
        const val BOOK = "Book A"
        const val MANGA_VOLUME = "Vol 01"
        const val LOOSE_FILE = "Ch 010.cbz"
    }
}
