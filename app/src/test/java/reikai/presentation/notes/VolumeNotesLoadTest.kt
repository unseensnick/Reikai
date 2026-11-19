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
import reikai.domain.chapter.LocalVolume
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.EpubChapterKey
import reikai.presentation.details.VolumeLabel
import kotlin.time.Instant

/** What a volume's notes page opens on, read from its series rather than carried by the screen. */
class VolumeNotesLoadTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a volume no longer in its series closes its notes`() {
        val model = model(key = "Gone", saved = emptyList())

        model.notes.missing.value shouldBe true
    }

    @Test
    fun `a volume with no notes written opens on an empty note`() {
        val model = model(key = BOOK, saved = emptyList())

        model.notes.state.value?.notes shouldBe ""
    }

    @Test
    fun `the page heads with the name the volume was given`() {
        val model = model(key = BOOK, saved = listOf(LocalVolume(BOOK, null, "Renamed")))

        model.notes.state.value?.subject?.label shouldBe VolumeLabel.Name("Renamed")
    }

    private fun model(key: String, saved: List<LocalVolume>) = VolumeNotesScreen.Model(
        ContentType.NOVELS,
        SERIES,
        key,
        CoroutineScope(SupervisorJob()),
        mockk<LocalVolumeRepository> {
            coEvery { loadedVolumes(ContentType.NOVELS) } returns mapOf(SERIES to saved)
            coEvery { loadedLooseFiles() } returns emptyMap()
        },
        mockk(),
        mockk(),
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
    }
}
