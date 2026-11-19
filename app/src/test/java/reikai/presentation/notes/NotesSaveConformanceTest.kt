package reikai.presentation.notes

import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesViewModel
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.chapter.LocalVolume
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.EpubChapterKey
import reikai.presentation.novel.notes.NovelNotesRoute
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Instant

/**
 * The save every notes editor runs: a manga's (upstream's model), a novel's and a local volume's. An unchanged note
 * writes nothing, and each change is written on Main at once, so the debounced saves and the one on leaving land in
 * the order they were made. The novel and volume pages edit through [NotesEditor], which restates upstream's rule,
 * so this pins it to upstream's.
 */
class NotesSaveConformanceTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    enum class Editor { MANGA, NOVEL, VOLUME }

    @ParameterizedTest
    @EnumSource(Editor::class)
    fun `an unchanged note writes nothing`(editor: Editor) {
        val (update, writes) = editor(editor)

        update(INITIAL)

        writes shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(Editor::class)
    fun `each change is written at once, in the order it was made`(editor: Editor) {
        val (update, writes) = editor(editor)

        update("b")
        update("c")

        writes shouldBe listOf("b", "c")
    }

    private fun editor(editor: Editor): Pair<(String) -> Unit, List<String>> {
        val writes = CopyOnWriteArrayList<String>()
        val appScope = CoroutineScope(SupervisorJob())
        val update: (String) -> Unit = when (editor) {
            Editor.MANGA -> MangaNotesViewModel(
                1L,
                appScope,
                mockk<GetManga> { coEvery { await(1L) } returns Manga.create().copy(id = 1L, notes = INITIAL) },
                mockk<UpdateMangaNotes> { coEvery { this@mockk(1L, any()) } answers { writes.add(secondArg()) } },
            )::updateNotes
            Editor.NOVEL -> NovelNotesRoute.Model(
                1L,
                appScope,
                mockk<NovelRepository> {
                    coEvery { getById(1L) } returns Novel.create().copy(id = 1L, notes = INITIAL)
                    coEvery { update(any<NovelUpdate>()) } answers { writes.add(firstArg<NovelUpdate>().notes!!) }
                },
            ).notes::update
            Editor.VOLUME -> VolumeNotesRoute.Model(
                ContentType.NOVELS,
                SERIES,
                BOOK,
                appScope,
                mockk<LocalVolumeRepository> {
                    coEvery { loadedVolumes(ContentType.NOVELS) } returns
                        mapOf(SERIES to listOf(LocalVolume(BOOK, null, null, INITIAL)))
                    coEvery { loadedLooseFiles() } returns emptyMap()
                    coEvery { setNotes(any(), any(), any(), any()) } answers { writes += arg<String>(3) }
                },
                mockk(),
                mockk(),
                mockk<NovelRepository> {
                    coEvery { getByUrlAndSource(SERIES, LocalNovelSource.ID) } returns
                        Novel.create().copy(id = 1L, url = SERIES)
                },
                mockk<NovelChapterRepository> { coEvery { getByNovelId(1L) } returns listOf(bookChapter()) },
            ).notes::update
        }
        return update to writes
    }

    private fun bookChapter() = NovelChapter(
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
    )

    private companion object {
        const val INITIAL = "a"
        const val SERIES = "series"
        const val BOOK = "v1"
    }
}
