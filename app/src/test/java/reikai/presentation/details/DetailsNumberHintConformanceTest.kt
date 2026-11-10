package reikai.presentation.details

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import eu.kanade.tachiyomi.ui.manga.mangaDetailsModel
import exh.debug.DebugToggles
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.chapter.EditChapterNumber
import reikai.domain.chapter.hiddenChapterKey
import reikai.domain.manga.MangaPreferences
import reikai.presentation.novel.details.NovelDetailsDialog
import reikai.presentation.novel.details.NovelDetailsState
import reikai.presentation.novel.details.NovelDetailsViewModel
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga

/**
 * A details page marks a chapter whose number is out of line with its own source's list, never one the
 * user hid, and its marker opens the number dialog on the suggestion: each model's wiring around the
 * shared ChapterNumberHint kernel, pinned once over both types.
 */
class DetailsNumberHintConformanceTest {

    /** Five chapters of one series, [NUMBERS] in the source's order; the third is the stray. */
    interface Probe {
        /** Each marked chapter's index in [NUMBERS], to its suggestion. */
        suspend fun marked(scope: TestScope, strayHidden: Boolean): Map<Int, Double?>

        /** Merged with a second source listing only [SECOND]: the marked chapters as (member, index). */
        suspend fun markedWhenMerged(scope: TestScope): Set<Pair<Int, Int>>

        /** The suggestion the number dialog opens on, opened from the stray's marker. */
        suspend fun dialogSuggestion(scope: TestScope): Double?
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Both read through Injekt in production; held at their shipped defaults.
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter(any()) } returns TriState.DISABLED
        mockkObject(DebugToggles.ENABLE_EXH_ROOT_REDIRECT)
        every { DebugToggles.ENABLE_EXH_ROOT_REDIRECT.enabled } returns DebugToggles.ENABLE_EXH_ROOT_REDIRECT.default
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a chapter out of line with its neighbours is marked with the number they leave free`(probe: Probe) =
        runTest {
            probe.marked(this, strayHidden = false) shouldBe mapOf(STRAY to 1135.0)
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a stray the user hid is not marked`(probe: Probe) = runTest {
        probe.marked(this, strayHidden = true) shouldBe emptyMap()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a merged series marks a stray from its own source's list`(probe: Probe) = runTest {
        // The stray is out of line only in its own source's list.
        probe.markedWhenMerged(this) shouldBe setOf(1 to 1)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the marker opens the number dialog on the suggestion`(probe: Probe) = runTest {
        probe.dialogSuggestion(this) shouldBe 1135.0
    }

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        private const val STRAY = 2

        // Real rows from a library: chapter 1135 listed as 1335.
        val NUMBERS = listOf(1133.0, 1134.0, 1335.0, 1136.0, 1137.0)

        // A second source's shorter list, whose middle chapter is the same stray.
        val SECOND = listOf(1134.0, 1335.0, 1136.0)

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun marked(scope: TestScope, strayHidden: Boolean): Map<Int, Double?> {
            val ids = NUMBERS.indices.map { 100L + it }
            val prefs = EmittingPreferenceStore()
            if (strayHidden) {
                MangaPreferences(
                    prefs,
                ).hiddenChapters().set(setOf(hiddenChapterKey(SOURCE.toString(), url(ids[STRAY]))))
            }
            return open(listOf(NUMBERS), prefs) { model ->
                val shown = if (strayHidden) NUMBERS.size - 1 else NUMBERS.size
                model.success { it.chapters.size == shown }.numberHints
                    .entries.associate { (id, hint) -> ids.indexOf(id) to hint.suggestion }
            }
        }

        override suspend fun markedWhenMerged(scope: TestScope): Set<Pair<Int, Int>> =
            open(listOf(NUMBERS.filter { it != 1335.0 }, SECOND)) { model ->
                model.success { it.mergeSources.size == 2 }.numberHints.keys
                    .mapTo(HashSet()) { id -> (id / 100 - 1).toInt() to (id % 100).toInt() }
            }

        override suspend fun dialogSuggestion(scope: TestScope): Double? = open(listOf(NUMBERS)) { model ->
            model.success { it.numberHints.isNotEmpty() }
            model.showChapterNumberDialog(100L + STRAY)
            (
                model.success {
                    it.dialog is MangaViewModel.Dialog.ChapterNumber
                }.dialog as MangaViewModel.Dialog.ChapterNumber
                )
                .edit.suggestion
        }

        /** One manga per list in [members], ids 1 up, chapter ids 100 * member + index, then [block]. */
        private suspend fun <T> open(
            members: List<List<Double>>,
            prefs: EmittingPreferenceStore = EmittingPreferenceStore(),
            block: suspend (MangaViewModel) -> T,
        ): T {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            members.forEachIndexed { m, numbers ->
                val mangaId = m + 1L
                driver.execute(
                    null,
                    "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                        "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                        "remote_update_strategy, state_chapter_next_update, state_chapter_fetch_interval, " +
                        "user_notes, remote_memo) VALUES ($mangaId, ${SOURCE + m}, '/manga$mangaId', 'Title', 0, 1, " +
                        "0, 0, 0, 1, 0, 0, 0, '', '{}')",
                    0,
                ).await()
                numbers.forEachIndexed { i, number ->
                    val id = 100L * mangaId + i
                    // Mihon's source order counts from the top of the source's list, newest first.
                    val order = numbers.size - 1 - i
                    driver.execute(
                        null,
                        "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                            "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, " +
                            "state_date_fetch, remote_date_upload, remote_memo) VALUES ($id, $mangaId, " +
                            "'${url(id)}', 'Chapter $number', NULL, 0, 0, 0, $number, $order, 0, 0, '{}')",
                        0,
                    ).await()
                }
            }
            val store = ViewModelStore()
            val model = mangaDetailsModel(
                1L,
                MangaRepositoryImpl(database),
                ChapterRepositoryImpl(database),
                members.indices.map { it + 1L }.toLongArray(),
                prefs = prefs,
                editChapterNumber = EditChapterNumber(
                    mockk { coEvery { getByOwner(any(), any()) } returns emptyMap() },
                    mockk(),
                ),
            ).also { store.put("manga", it) }
            return try {
                block(model)
            } finally {
                // The model reads on the real IO dispatcher, so it stops before the database closes.
                val job = model.viewModelScope.coroutineContext.job
                store.clear()
                job.join()
                driver.close()
            }
        }

        private fun url(chapterId: Long) = "/chapter/$chapterId"

        private suspend fun MangaViewModel.success(
            ready: (MangaViewModel.State.Success) -> Boolean,
        ): MangaViewModel.State.Success = withContext(Dispatchers.Default) {
            withTimeout(30_000) { state.filterIsInstance<MangaViewModel.State.Success>().first(ready) }
        }

        private companion object {
            const val SOURCE = 10L
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun marked(scope: TestScope, strayHidden: Boolean): Map<Int, Double?> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novelId = harness.novel(harness.source("alpha"))
                val ids = NUMBERS.mapIndexed { i, number -> harness.chapter(novelId, number, sourceOrder = i.toLong()) }
                if (strayHidden) {
                    harness.novelPreferences.hiddenChapters().set(setOf(hiddenChapterKey("alpha", ids[STRAY].url)))
                }
                val shown = if (strayHidden) NUMBERS.size - 1 else NUMBERS.size
                harness.openDetails(novelId).awaitLoaded { it.chapters.size == shown }.numberHints
                    .entries.associate { (id, hint) -> ids.indexOfFirst { it.id == id } to hint.suggestion }
            }

        override suspend fun markedWhenMerged(scope: TestScope): Set<Pair<Int, Int>> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val leading = harness.novel(harness.source("alpha"))
                val other = harness.novel(harness.source("beta"))
                val members = listOf(
                    NUMBERS.filter { it != 1335.0 }
                        .mapIndexed { i, number -> harness.chapter(leading, number, sourceOrder = i.toLong()).id },
                    SECOND.mapIndexed { i, number -> harness.chapter(other, number, sourceOrder = i.toLong()).id },
                )
                harness.merge(leading, other)
                harness.openDetails(leading).awaitLoaded { it.mergeSources.size == 2 && it.chapters.size == 5 }
                    .numberHints.keys
                    .mapTo(HashSet()) { id ->
                        members.indexOfFirst { id in it }.let { m -> m to members[m].indexOf(id) }
                    }
            }

        override suspend fun dialogSuggestion(scope: TestScope): Double? =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novelId = harness.novel(harness.source("alpha"))
                val stray = NUMBERS.mapIndexed { i, number ->
                    harness.chapter(novelId, number, sourceOrder = i.toLong())
                }[STRAY]
                val model = harness.openDetails(novelId)
                model.awaitLoaded { it.chapters.size == 5 }
                model.showChapterNumberDialog(stray.id)
                val dialog = model.awaitLoaded { it.dialog is NovelDetailsDialog.ChapterNumber }.dialog
                (dialog as NovelDetailsDialog.ChapterNumber).edit.suggestion
            }

        private suspend fun NovelDetailsViewModel.awaitLoaded(
            predicate: (NovelDetailsState.Loaded) -> Boolean,
        ): NovelDetailsState.Loaded = withContext(Dispatchers.Default) {
            withTimeout(10_000) { state.filterIsInstance<NovelDetailsState.Loaded>().first(predicate) }
        }
    }
}
