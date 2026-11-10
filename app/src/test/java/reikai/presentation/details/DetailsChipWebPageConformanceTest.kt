package reikai.presentation.details

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.domain.source.SourceKey
import reikai.presentation.novel.details.NovelDetailsState
import reikai.presentation.reader.NovelReaderViewModelHarness
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/**
 * The page a merged entry's WebView, Share, Copy link and assistant link open follows the selected source
 * chip to that member's own address, through each type's real details model. Pinned once over both types.
 */
class DetailsChipWebPageConformanceTest {

    /** A merged entry of an anchor and a sibling on two installed sources, opened through the anchor. */
    interface Probe {
        /** The page shown once the sibling's chip is picked, and the sibling's own page. */
        suspend fun siblingChipPage(scope: TestScope): Pair<EntryWebPage?, EntryWebPage>
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
    fun `a merged entry's page follows the selected chip to that source's own address`(probe: Probe) = runTest {
        val (shown, siblings) = probe.siblingChipPage(this)

        shown shouldBe siblings
    }

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun siblingChipPage(scope: TestScope): Pair<EntryWebPage?, EntryWebPage> {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            driver.seedManga(ANCHOR, chapterId = 10L, sourceId = ANCHOR_SOURCE)
            driver.seedManga(SIBLING, chapterId = 20L, sourceId = SIBLING_SOURCE)
            val sources = mockk<SourceManager>(relaxed = true) {
                coEvery { getOrStub(any()) } answers { httpSource(firstArg<Long>()) }
            }
            val store = ViewModelStore()
            val model = mangaDetailsModel(
                ANCHOR,
                MangaRepositoryImpl(database),
                ChapterRepositoryImpl(database),
                longArrayOf(ANCHOR, SIBLING),
                sourceManager = sources,
            ).also { store.put("manga", it) }
            return try {
                val shown = withContext(Dispatchers.Default) {
                    withTimeout(30_000) {
                        val success = model.state.filterIsInstance<MangaViewModel.State.Success>()
                        // A chip outside the group is refused, so pick it once the group has resolved.
                        success.first { it.mergeSources.size == 2 }
                        model.selectSource(SIBLING)
                        success.first { it.webPage?.viewed == EntryId.Manga(SIBLING) }.webPage
                    }
                }
                shown to
                    EntryWebPage(
                        "https://$SIBLING_SOURCE.example/manga$SIBLING",
                        SourceKey.Manga(SIBLING_SOURCE),
                        EntryId.Manga(SIBLING),
                    )
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
            every { getMangaUrl(any()) } answers { "https://$sourceId.example" + firstArg<SManga>().url }
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

        override suspend fun siblingChipPage(scope: TestScope): Pair<EntryWebPage?, EntryWebPage> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val anchor = harness.novel(harness.source("alpha"))
                val sibling = harness.novel(harness.source("beta"))
                harness.merge(anchor, sibling)
                val model = harness.openDetails(anchor)
                val shown = withContext(Dispatchers.Default) {
                    withTimeout(10_000) {
                        // A chip outside the group is refused, so pick it once the group has resolved.
                        model.state.first { it is NovelDetailsState.Loaded && it.mergeSources.size == 2 }
                        model.selectSource(sibling)
                        (
                            model.state.first {
                                it is NovelDetailsState.Loaded && it.webPage?.viewed == EntryId.Novel(sibling)
                            } as NovelDetailsState.Loaded
                            ).webPage
                    }
                }
                shown to
                    EntryWebPage(
                        "https://beta.example/novel/beta/Novel",
                        SourceKey.Novel("beta"),
                        EntryId.Novel(sibling),
                    )
            }
    }
}
