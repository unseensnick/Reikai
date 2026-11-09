package reikai.presentation.details

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.manga.mangaDetailsModel
import eu.kanade.tachiyomi.ui.manga.seedManga
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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.presentation.reader.NovelReaderViewModelHarness
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager

/**
 * The notes a merged entry's details page shows follow the selected source chip to that member's own
 * notes, and an edit to them reaches the page, through each type's real details model and adapter. The
 * All view keeps the anchor's. Pinned once over both types.
 */
class DetailsChipNotesConformanceTest {

    /** A merged entry of an anchor and a sibling, each with its own notes, opened through the anchor. */
    interface Probe {
        /** The notes shown on the sibling's chip ([chip]) or under All, after [edit] is saved as its notes. */
        suspend fun notesShown(scope: TestScope, chip: Boolean, edit: String? = null): String?
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Both read through Injekt in production; held at their shipped defaults.
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter } returns TriState.DISABLED
        mockkObject(DebugToggles.ENABLE_EXH_ROOT_REDIRECT)
        every { DebugToggles.ENABLE_EXH_ROOT_REDIRECT.enabled } returns DebugToggles.ENABLE_EXH_ROOT_REDIRECT.default
        // A header naming one source reads the language preferences off Injekt, which no test registers.
        mockkStatic(SOURCE_EXTENSIONS_FILE)
        every { any<Source>().getNameForMangaInfo() } returns "source"
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `All shows the anchor's notes`(probe: Probe) = runTest {
        probe.notesShown(this, chip = false) shouldBe ANCHOR_NOTES
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a source chip shows that source's own notes`(probe: Probe) = runTest {
        probe.notesShown(this, chip = true) shouldBe SIBLING_NOTES
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an edit to a chip's notes reaches the page`(probe: Probe) = runTest {
        probe.notesShown(this, chip = true, edit = EDITED_NOTES) shouldBe EDITED_NOTES
    }

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        private const val SOURCE_EXTENSIONS_FILE = "eu.kanade.tachiyomi.source.SourceExtensionsKt"
        const val ANCHOR_NOTES = "anchor notes"
        const val SIBLING_NOTES = "sibling notes"
        const val EDITED_NOTES = "edited notes"

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())

        /** Settles the page on the picked view, then saves [edit] through [write] and waits for it to show. */
        suspend fun EntryDetailsBehavior.settledNotes(
            viewed: Long,
            select: (() -> Unit)?,
            edit: String?,
            write: suspend (String) -> Unit,
        ): String? = withContext(Dispatchers.Default) {
            withTimeout(30_000) {
                val loaded = state.filterIsInstance<EntryDetailsScreenState.Loaded>()
                // A chip outside the group is refused, so pick it once the group has resolved.
                loaded.first { it.mergeSources.size == 2 }
                select?.invoke()
                val shown = loaded.first { it.mergeSources.size == 2 && it.viewedEntryId == viewed }
                if (edit == null) return@withTimeout shown.details.notes
                write(edit)
                withTimeoutOrNull(5_000) { loaded.first { it.details.notes == edit } }?.details?.notes
                    ?: loaded.first().details.notes
            }
        }
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun notesShown(scope: TestScope, chip: Boolean, edit: String?): String? {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            val mangas = MangaRepositoryImpl(database)
            driver.seedManga(ANCHOR, chapterId = 10L, sourceId = ANCHOR_SOURCE)
            driver.seedManga(SIBLING, chapterId = 20L, sourceId = SIBLING_SOURCE)
            mangas.update(MangaUpdate(ANCHOR) { notes = ANCHOR_NOTES })
            mangas.update(MangaUpdate(SIBLING) { notes = SIBLING_NOTES })
            val sources = mockk<SourceManager>(relaxed = true) {
                coEvery { getOrStub(any()) } answers { httpSource(firstArg<Long>()) }
            }
            val store = ViewModelStore()
            val model = mangaDetailsModel(
                ANCHOR,
                mangas,
                ChapterRepositoryImpl(database),
                longArrayOf(ANCHOR, SIBLING),
                sourceManager = sources,
            ).also { store.put("manga", it) }
            return try {
                MangaEntryAdapter(model, mockk(relaxed = true), noDuplicateMarking(), noVolumePicking())
                    .settledNotes(
                        viewed = if (chip) SIBLING else ANCHOR,
                        select = { model.selectSource(SIBLING) }.takeIf { chip },
                        edit = edit,
                    ) { mangas.update(MangaUpdate(SIBLING) { notes = it }) }
            } finally {
                // The model reads on the real IO dispatcher, so it stops before the database closes.
                val job = model.viewModelScope.coroutineContext.job
                store.clear()
                job.join()
                driver.close()
            }
        }

        private fun httpSource(sourceId: Long): HttpSource = mockk<HttpSource>(relaxed = true) {
            every { id } returns sourceId
            every { name } returns "src$sourceId"
        }

        private companion object {
            const val ANCHOR = 1L
            const val SIBLING = 2L
            const val ANCHOR_SOURCE = 7L
            const val SIBLING_SOURCE = 8L
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun notesShown(scope: TestScope, chip: Boolean, edit: String?): String? =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val anchor = harness.novel(harness.source("alpha"))
                val sibling = harness.novel(harness.source("beta"))
                harness.updateNovel(anchor) { notes = ANCHOR_NOTES }
                harness.updateNovel(sibling) { notes = SIBLING_NOTES }
                harness.merge(anchor, sibling)
                val model = harness.openDetails(anchor)
                NovelEntryAdapter(model, mockk(relaxed = true), noDuplicateMarking(), noVolumePicking())
                    .settledNotes(
                        viewed = if (chip) sibling else anchor,
                        select = { model.selectSource(sibling) }.takeIf { chip },
                        edit = edit,
                    ) { harness.updateNovel(sibling) { notes = it } }
            }
    }
}
