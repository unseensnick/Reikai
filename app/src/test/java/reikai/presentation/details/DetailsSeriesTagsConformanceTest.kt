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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.model.CustomNovelInfo
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaRemoteUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/**
 * A merged series' details page under All shows the series' tags as the library reads them, for both types,
 * and its header's other titles are the raw titles of the sources the view stands for.
 */
class DetailsSeriesTagsConformanceTest {

    /** A two-source series, the anchor and one sibling, as the details page of the anchor opens it. */
    class Series(
        val anchorTags: List<String> = ANCHOR_TAGS,
        val siblingTags: List<String> = SIBLING_TAGS,
        val customTags: List<String>? = null,
        val customTitle: String? = null,
        val merged: Boolean = true,
    )

    class Opened(
        val behavior: EntryDetailsBehavior,
        val anchor: Long,
        val sibling: Long,
        val selectSibling: () -> Unit,
        val retagSibling: suspend (List<String>) -> Unit,
    )

    interface Probe {
        suspend fun <T> opened(scope: TestScope, series: Series, block: suspend (Opened) -> T): T
    }

    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter(any()) } returns TriState.DISABLED
        mockkObject(DebugToggles.ENABLE_EXH_ROOT_REDIRECT)
        every { DebugToggles.ENABLE_EXH_ROOT_REDIRECT.enabled } returns DebugToggles.ENABLE_EXH_ROOT_REDIRECT.default
        mockkStatic(SOURCE_EXTENSIONS_FILE)
        every { any<Source>().getNameForMangaInfo(any()) } returns "source"
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        resetVirtualDispatchers()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `All shows every source's tags, the anchor's first and each tag once`(probe: Probe) = runTest {
        probe.opened(this, Series()) { it.settled(it.anchor).details.tags } shouldBe
            listOf("Action", "Drama", "Comedy")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a source chip shows that source's own tags`(probe: Probe) = runTest {
        probe.opened(this, Series()) { it.settled(it.sibling, it.selectSibling).details.tags } shouldBe SIBLING_TAGS
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `Edit info tags replace every source's own under All`(probe: Probe) = runTest {
        probe.opened(this, Series(customTags = listOf("Mine"))) { it.settled(it.anchor).details.tags } shouldBe
            listOf("Mine")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a sibling's new tags reach All with no chapter change`(probe: Probe) = runTest {
        probe.opened(this, Series()) { opened ->
            opened.settled(opened.anchor)
            opened.retagSibling(listOf("Horror"))
            opened.settled(opened.anchor) { "Horror" in it.details.tags.orEmpty() }.details.tags
        } shouldBe listOf("Action", "Drama", "Horror")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a source chip under an Edit info title lists that source's own title`(probe: Probe) = runTest {
        probe.opened(this, Series(customTitle = "Mine")) {
            it.settled(it.sibling, it.selectSibling).details.header.altTitles
        } shouldBe listOf(SIBLING_TITLE)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `All under an Edit info title lists every source's own title`(probe: Probe) = runTest {
        probe.opened(this, Series(customTitle = "Mine")) {
            it.settled(it.anchor) { state ->
                SIBLING_TITLE in state.details.header.altTitles
            }.details.header.altTitles.size
        } shouldBe 2
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an unmerged entry under an Edit info title lists its own source's title`(probe: Probe) = runTest {
        probe.opened(this, Series(customTitle = "Mine", merged = false)) { opened ->
            withTimeout(30_000) {
                opened.behavior.state.filterIsInstance<EntryDetailsScreenState.Loaded>()
                    .first { it.details.header.title == "Mine" }
            }.details.header.altTitles
        } shouldBe listOf(ANCHOR_TITLE)
    }

    @Test
    fun `a gallery anchor keeps its grouped chips and shows the other sources' tags in a row of their own`() =
        runTest {
            val series = Series(anchorTags = listOf("female: x", "male: y"), siblingTags = listOf("Comedy"))

            MangaProbe().opened(this, series) { opened ->
                val gallery = opened.settled(opened.anchor).capabilities.mangaGallery!!
                gallery.rawGenre to gallery.chips()?.tags?.mapValues { (_, tags) -> tags.map { it.text } }
            } shouldBe (
                listOf("female: x", "male: y") to
                    mapOf("female" to listOf("x"), "male" to listOf("y"), "" to listOf("Comedy"))
                )
        }

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        private const val SOURCE_EXTENSIONS_FILE = "eu.kanade.tachiyomi.source.SourceExtensionsKt"
        val ANCHOR_TAGS = listOf("Action", "Drama")
        val SIBLING_TAGS = listOf("drama", "Comedy")
        const val ANCHOR_TITLE = "Anchor title"
        const val SIBLING_TITLE = "Sibling title"

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())

        /** The loaded state viewing [viewed] with both sources' chips, once [ready] holds; [select] picks a chip first. */
        suspend fun Opened.settled(
            viewed: Long,
            select: (() -> Unit)? = null,
            ready: (EntryDetailsScreenState.Loaded) -> Boolean = { true },
        ): EntryDetailsScreenState.Loaded = withTimeout(30_000) {
            val loaded = behavior.state.filterIsInstance<EntryDetailsScreenState.Loaded>()
            loaded.first { it.mergeSources.size == 2 }
            select?.invoke()
            loaded.first { it.mergeSources.size == 2 && it.viewedEntryId == viewed && ready(it) }
        }
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun <T> opened(scope: TestScope, series: Series, block: suspend (Opened) -> T): T {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            val mangas = MangaRepositoryImpl(database)
            driver.seedManga(ANCHOR, chapterId = 10L, sourceId = ANCHOR_SOURCE)
            driver.seedManga(SIBLING, chapterId = 20L, sourceId = SIBLING_SOURCE)
            mangas.retag(ANCHOR, series.anchorTags, ANCHOR_TITLE)
            mangas.retag(SIBLING, series.siblingTags, SIBLING_TITLE)
            val sources = mockk<SourceManager>(relaxed = true) {
                coEvery { getOrStub(any()) } answers { httpSource(firstArg<Long>()) }
            }
            val store = ViewModelStore()
            val model = mangaDetailsModel(
                ANCHOR,
                mangas,
                ChapterRepositoryImpl(database),
                if (series.merged) longArrayOf(ANCHOR, SIBLING) else longArrayOf(ANCHOR),
                sourceManager = sources,
                customInfo = if (series.customTags == null && series.customTitle == null) {
                    emptyMap()
                } else {
                    mapOf(ANCHOR to CustomMangaInfo(ANCHOR, title = series.customTitle, genre = series.customTags))
                },
            ).also { store.put("manga", it) }
            return try {
                block(
                    Opened(
                        MangaEntryAdapter(model, mockk(relaxed = true), noDuplicateMarking(), noVolumePicking()),
                        ANCHOR,
                        SIBLING,
                        selectSibling = { model.selectSource(SIBLING) },
                        retagSibling = { mangas.retag(SIBLING, it) },
                    ),
                )
            } finally {
                val job = model.viewModelScope.coroutineContext.job
                store.clear()
                job.join()
                driver.close()
            }
        }

        /** Stores [tags] (and a [title]) as [id]'s source values, as a details refresh writes them. */
        private suspend fun MangaRepository.retag(id: Long, tags: List<String>, title: String? = null) {
            val manga = getMangaById(id)
            updateRemote(
                MangaRemoteUpdate(
                    id = id,
                    title = title ?: manga.title,
                    author = manga.author,
                    artist = manga.artist,
                    description = manga.description,
                    genre = tags,
                    status = manga.status,
                    thumbnailUrl = manga.thumbnailUrl,
                    updateStrategy = manga.updateStrategy,
                    memo = manga.memo,
                    initialized = manga.initialized,
                    coverLastModified = manga.coverLastModified,
                ),
            )
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

        override suspend fun <T> opened(scope: TestScope, series: Series, block: suspend (Opened) -> T): T =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val anchor = harness.novel(harness.source("alpha"), title = ANCHOR_TITLE)
                val sibling = harness.novel(harness.source("beta"), title = SIBLING_TITLE)
                harness.updateNovel(anchor) { genre = series.anchorTags }
                harness.updateNovel(sibling) { genre = series.siblingTags }
                if (series.customTags != null || series.customTitle != null) {
                    harness.customInfo[anchor] =
                        CustomNovelInfo(anchor, title = series.customTitle, genre = series.customTags)
                }
                if (series.merged) harness.merge(anchor, sibling)
                val model = harness.openDetails(anchor)
                block(
                    Opened(
                        NovelEntryAdapter(model, mockk(relaxed = true), noDuplicateMarking(), noVolumePicking()),
                        anchor,
                        sibling,
                        selectSibling = { model.selectSource(sibling) },
                        retagSibling = { tags -> harness.updateNovel(sibling) { genre = tags } },
                    ),
                )
            }
    }
}
