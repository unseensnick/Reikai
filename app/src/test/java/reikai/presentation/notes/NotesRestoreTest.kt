package reikai.presentation.notes

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.navigation3.runtime.NavKey
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import eu.kanade.tachiyomi.data.backup.MangaRestoreHarness
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesViewModel
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import mihon.core.navigation.MangaNotesRoute
import mihon.core.navigation.MangaRoute
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import reikai.data.chapter.LocalVolumeRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.EpubChapterKey
import reikai.presentation.ComposeTestHost
import reikai.presentation.TestNavHost
import reikai.presentation.details.detailsRoute
import reikai.presentation.notes.VolumeNotesScreen
import reikai.presentation.novel.details.NovelRoute
import reikai.presentation.novel.notes.NovelNotesRoute
import reikai.presentation.novel.notes.NovelNotesScreen
import reikai.presentation.roundTrip
import reikai.presentation.testNavigator
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.model.Manga
import kotlin.reflect.KClass
import kotlin.time.Instant

/**
 * Android saves a screen's arguments when it closes the app in the background and builds the screen again from them.
 * A notes page that carried its notes would come back showing that copy, and the next edit would save over the
 * notes written since. Each editor (a manga's, a novel's, a local volume's) reads its notes when it opens instead,
 * and closes when what it notes is gone.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class NotesRestoreTest(private val editor: Editor) {

    enum class Editor { MANGA, NOVEL, VOLUME }

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: MangaRestoreHarness
    private lateinit var novels: NovelRepositoryImpl
    private lateinit var novelChapters: NovelChapterRepositoryImpl
    private lateinit var volumes: LocalVolumeRepositoryImpl

    // Unconfined, so the held volume rows read on the writing thread: the test database is one connection
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var mangaId = 0L
    private var novelId = 0L
    private var shown by mutableStateOf(true)

    @Before
    fun setUp() = runTest {
        // The sandbox's own copy of the driver would be invisible to the plain JDBC tests after this one
        Class.forName("org.sqlite.JDBC", true, ClassLoader.getSystemClassLoader())
        db = MangaRestoreHarness.create()
        novels = NovelRepositoryImpl(db.database)
        novelChapters = NovelChapterRepositoryImpl(db.database)
        volumes = LocalVolumeRepositoryImpl(db.database, appScope)
        mangaId = db.insert(Manga.create().copy(source = 1L, url = "/m/1", title = "One"))
        val novel = novels.insertOrGet(Novel.create().copy(source = LocalNovelSource.ID, url = SERIES, title = "One"))!!
        novelId = novel.id
        novelChapters.insert(
            NovelChapter(
                id = 0,
                novelId = novelId,
                url = LocalNovelSource.chapterPath(SERIES, BOOK, EpubChapterKey("ch1.xhtml")),
                name = "$BOOK - Chapter 1",
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
    }

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
    }

    @Test
    fun `a notes page restored after the app was closed keeps the notes written before`() {
        val pushed = screen()
        write("Alpha")
        host(roundTrip(pushed))

        typeAndLeave("Beta ")

        stored() shouldContain "Alpha"
    }

    // A series' details page under its gone notes cannot load it either, so it closes too; a volume's series may
    // still be there, so its page stays.
    @Test
    fun `a notes page whose subject is gone closes, with its series' details page when that is gone too`() {
        val (details, gone, landing) = when (editor) {
            Editor.MANGA -> Triple(EntryId.Manga(GONE).detailsRoute(), MangaNotesRoute(GONE), BELOW)
            Editor.NOVEL -> Triple(EntryId.Novel(GONE).detailsRoute(), NovelNotesRoute(GONE), BELOW)
            Editor.VOLUME -> Triple(
                EntryId.Novel(novelId).detailsRoute(),
                VolumeNotesRoute(ContentType.NOVELS, "Gone", BOOK),
                DETAILS,
            )
        }
        host(Below, details, gone)

        compose.waitUntil { compose.onAllNodes(hasText(landing)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun typeAndLeave(text: String) {
        compose.waitUntil { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.waitForIdle()
        shown = false
        compose.waitForIdle()
    }

    private fun screen(): NavKey = when (editor) {
        Editor.MANGA -> MangaNotesRoute(mangaId)
        Editor.NOVEL -> NovelNotesRoute(novelId)
        Editor.VOLUME -> VolumeNotesRoute(ContentType.NOVELS, SERIES, BOOK)
    }

    private fun write(notes: String) = runTest {
        when (editor) {
            Editor.MANGA -> UpdateMangaNotes(db.mangas)(mangaId, notes)
            Editor.NOVEL -> novels.update(NovelUpdate(novelId) { this.notes = notes })
            Editor.VOLUME -> volumes.setNotes(ContentType.NOVELS, SERIES, BOOK, notes)
        }
    }

    private fun stored(): String {
        var notes = ""
        runTest {
            notes = when (editor) {
                Editor.MANGA -> GetManga(db.mangas).await(mangaId)!!.notes
                Editor.NOVEL -> novels.getById(novelId)!!.notes
                Editor.VOLUME -> volumes.loadedVolumes(ContentType.NOVELS)[SERIES]?.single()?.notes.orEmpty()
            }
        }
        return notes
    }

    private val factories: Map<KClass<out ManualViewModelAssistedFactory>, () -> ManualViewModelAssistedFactory> =
        mapOf(
            MangaNotesViewModel.Factory::class to {
                object : MangaNotesViewModel.Factory {
                    override fun create(mangaId: Long) =
                        MangaNotesViewModel(mangaId, appScope, GetManga(db.mangas), UpdateMangaNotes(db.mangas))
                }
            },
            NovelNotesRoute.Model.Factory::class to {
                object : NovelNotesRoute.Model.Factory {
                    override fun create(novelId: Long) = NovelNotesRoute.Model(novelId, appScope, novels)
                }
            },
            VolumeNotesRoute.Model.Factory::class to {
                object : VolumeNotesRoute.Model.Factory {
                    override fun create(type: ContentType, series: String, volumeKey: String) =
                        VolumeNotesRoute.Model(
                            type,
                            series,
                            volumeKey,
                            appScope,
                            volumes,
                            GetMangaByUrlAndSourceId(db.mangas),
                            GetChaptersByMangaId(db.chapters),
                            novels,
                            novelChapters,
                        )
                }
            },
        )

    private fun host(vararg routes: NavKey) = compose.setContent {
        ComposeTestHost(assistedFactories = factories) {
            if (shown) {
                TestNavHost(remember { testNavigator(*routes) }) {
                    entry<Below> { Text(BELOW) }
                    entry<MangaRoute> { Text(DETAILS) }
                    entry<NovelRoute> { Text(DETAILS) }
                    entry<MangaNotesRoute> { MangaNotesScreen(it.mangaId) }
                    entry<NovelNotesRoute> { NovelNotesScreen(it.novelId) }
                    entry<VolumeNotesRoute> { VolumeNotesScreen(it.type, it.series, it.volumeKey) }
                }
            }
        }
    }

    /** The page a notes page was opened over. */
    @Serializable
    data object Below : NavKey

    private companion object {
        const val SERIES = "Series"
        const val BOOK = "Book A"
        const val BELOW = "below"
        const val DETAILS = "details"
        const val GONE = 1000L

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun editors() = Editor.entries.map { arrayOf(it) }
    }
}
