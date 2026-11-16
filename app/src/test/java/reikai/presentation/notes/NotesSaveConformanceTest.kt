package reikai.presentation.notes

import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
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
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelUpdate
import reikai.presentation.novel.notes.NovelNotesScreen
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The save every notes editor runs: a manga's (upstream's model), a novel's and a local volume's. An unchanged note
 * writes nothing, and each change is written on Main at once, so the debounced saves and the one on leaving land in
 * the order they were made. The novel and volume models restate upstream's rule, so this pins them to it.
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
            Editor.MANGA -> MangaNotesScreen.Model(
                Manga.create().copy(id = 1L, notes = INITIAL),
                appScope,
                mockk<UpdateMangaNotes> { coEvery { this@mockk(1L, any()) } answers { writes.add(secondArg()) } },
            )::updateNotes
            Editor.NOVEL -> NovelNotesScreen.Model(
                1L,
                INITIAL,
                appScope,
                mockk<NovelRepository> {
                    coEvery { update(any<NovelUpdate>()) } answers { writes.add(firstArg<NovelUpdate>().notes!!) }
                },
            )::updateNotes
            Editor.VOLUME -> VolumeNotesScreen.Model(
                ContentType.NOVELS,
                "series",
                "v1",
                INITIAL,
                appScope,
                mockk<LocalVolumeRepository> {
                    coEvery { setNotes(any(), any(), any(), any()) } answers { writes += arg<String>(3) }
                },
            )::updateNotes
        }
        return update to writes
    }

    private companion object {
        const val INITIAL = "a"
    }
}
