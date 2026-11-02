package reikai.presentation.browse.feed

import androidx.lifecycle.viewModelScope
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.library.ContentType
import reikai.domain.novel.FavoritedNovels
import reikai.domain.source.MAX_FEED_ROWS
import reikai.domain.source.ReikaiSourcePreferences
import reikai.domain.source.SourceKey
import reikai.domain.source.model.FeedSavedSearch
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.testAppScope
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import eu.kanade.tachiyomi.source.Source as MangaSource
import tachiyomi.domain.source.model.Source as DomainSource

/**
 * A feed row follows its own type's source registry: a row built while its source was missing reads
 * unavailable, and installing the source afterwards fills it without leaving Browse, for both types.
 */
class FeedViewModelTest {

    // viewModelScope is Main-based, so the model needs a Main the test controls.
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    private val models = mutableListOf<FeedViewModel>()

    // Before resetMain: a model's IO collectors otherwise resume onto a Main that is gone, and the
    // failure lands in whichever test class runs next.
    @AfterEach
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a row whose source is installed after the feed was built fills in`(probe: FeedRegistryProbe) = runTest {
        val model = feedViewModel(probe)
        settle { model.state.first { it.loaded } }

        probe.install()

        settle { model.state.first { it.entries.single().row.state !is EntrySearchState.Unavailable } }
            .entries.single().sourceName shouldBe SOURCE_NAME
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a hidden type's rows leave the feed`(probe: FeedRegistryProbe) = runTest {
        val preferences = ReikaiSourcePreferences(EmittingPreferenceStore())
        preferences.shownContent.set(
            ContentType.entries.first { it != ContentType.ALL && it != probe.key.contentType },
        )
        val model = feedViewModel(probe, preferences = preferences)

        settle { model.state.first { it.loaded } }.entries shouldBe emptyList()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a shown type's rows stay in the feed`(probe: FeedRegistryProbe) = runTest {
        val preferences = ReikaiSourcePreferences(EmittingPreferenceStore())
        preferences.shownContent.set(probe.key.contentType)
        val model = feedViewModel(probe, preferences = preferences)

        settle { model.state.first { it.loaded } }.entries.size shouldBe 1
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `rows a hidden type holds leave the shown type room to add`(probe: FeedRegistryProbe) = runTest {
        val preferences = ReikaiSourcePreferences(EmittingPreferenceStore())
        preferences.shownContent.set(probe.otherType)
        val hidden = List(MAX_FEED_ROWS) { FeedSavedSearch(it.toLong(), probe.key, null, true, it.toLong()) }
        val model = feedViewModel(probe, rows = flowOf(hidden), preferences = preferences)

        model.openAddDialog()

        (settle { model.state.first { it.dialog != null } }.dialog is FeedDialog.TooManyRows) shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the add picker leaves out a hidden type's sources`(probe: FeedRegistryProbe) = runTest {
        val preferences = ReikaiSourcePreferences(EmittingPreferenceStore())
        preferences.shownContent.set(probe.otherType)
        probe.install()
        val model = feedViewModel(probe, rows = flowOf(emptyList()), preferences = preferences)

        model.openAddDialog()

        val picker = settle { model.state.first { it.dialog != null } }.dialog as FeedDialog.PickSource
        picker.sources shouldBe emptyList()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the add picker keeps a shown type's sources`(probe: FeedRegistryProbe) = runTest {
        val preferences = ReikaiSourcePreferences(EmittingPreferenceStore())
        preferences.shownContent.set(probe.key.contentType)
        probe.install()
        val model = feedViewModel(probe, rows = flowOf(emptyList()), preferences = preferences)

        model.openAddDialog()

        val picker = settle { model.state.first { it.dialog != null } }.dialog as FeedDialog.PickSource
        picker.sources.map { it.key } shouldBe listOf(probe.key)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a drag under Show leaves the hidden rows where they were`(probe: FeedRegistryProbe) = runTest {
        val other = probe.otherKey
        val written = CompletableDeferred<List<Long>>()
        val rows = flowOf(
            listOf(
                FeedSavedSearch(1L, other, null, true, 0L),
                FeedSavedSearch(2L, probe.key, null, true, 1L),
                FeedSavedSearch(3L, other, null, true, 2L),
                FeedSavedSearch(4L, probe.key, null, true, 3L),
            ),
        )
        val preferences = ReikaiSourcePreferences(EmittingPreferenceStore())
        preferences.shownContent.set(probe.key.contentType)
        val model = feedViewModel(probe, rows = rows, preferences = preferences, onOrders = written::complete)

        model.reorder(listOf(4L, 2L))

        settle { written.await() } shouldBe listOf(1L, 4L, 3L, 2L)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a row whose source is gone is named by the name it had`(probe: FeedRegistryProbe) = runTest {
        val model = feedViewModel(probe)

        val entry = settle { model.state.first { it.loaded } }.entries.single()

        listOf(entry.row.name, entry.sourceName) shouldBe listOf(STORED_NAME, STORED_NAME)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a feed change made while the feed is hidden waits until it is shown`(probe: FeedRegistryProbe) = runTest {
        val rows = MutableStateFlow(listOf(FeedSavedSearch(1L, probe.key, null, true, 0L)))
        val model = feedViewModel(probe, rows)
        settle { model.state.first { it.loaded } }
        advanceTimeBy(6.seconds)
        runCurrent()

        rows.value += FeedSavedSearch(2L, probe.key, null, true, 1L)
        withContext(Dispatchers.Default) { delay(500.milliseconds) }

        model.state.value.entries.size shouldBe 1
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a feed change held while hidden lands once the feed is shown`(probe: FeedRegistryProbe) = runTest {
        val rows = MutableStateFlow(listOf(FeedSavedSearch(1L, probe.key, null, true, 0L)))
        val model = feedViewModel(probe, rows)
        settle { model.state.first { it.loaded } }
        advanceTimeBy(6.seconds)
        runCurrent()
        rows.value += FeedSavedSearch(2L, probe.key, null, true, 1L)

        settle { model.state.first { it.entries.size == 2 } }.entries.size shouldBe 2
    }

    private fun feedViewModel(
        probe: FeedRegistryProbe,
        rows: Flow<List<FeedSavedSearch>> = flowOf(listOf(FeedSavedSearch(1L, probe.key, null, true, 0L))),
        preferences: ReikaiSourcePreferences = ReikaiSourcePreferences(EmittingPreferenceStore()),
        onOrders: (List<Long>) -> Unit = {},
    ) = FeedViewModel(
        appScope = testAppScope(),
        feedRepository = mockk {
            every { subscribeGlobal() } returns rows
            coEvery { countGlobal() } coAnswers { rows.first().size.toLong() }
            coEvery { updateOrders(any()) } answers { onOrders(firstArg()) }
        },
        savedSearchRepository = mockk { coEvery { getAll() } returns emptyList() },
        preferences = preferences,
        novelRepository = mockk { every { getFavoritedKeysAsFlow() } returns flowOf(FavoritedNovels.None) },
        getManga = mockk(relaxed = true),
        mangaAdder = mockk(relaxed = true),
        novelAdder = mockk(relaxed = true),
        sourceManager = probe.mangaRegistry,
        getEnabledSources = mockk { every { subscribe() } answers { flowOf(probe.enabledManga) } },
        networkToLocalManga = mockk(relaxed = true),
        novelSourceManager = probe.novelRegistry,
        getEnabledNovelSources = mockk { coEvery { get() } answers { probe.enabledNovels } },
    ).also(models::add)

    companion object {
        const val SOURCE_NAME = "Installed later"
        const val STORED_NAME = "Stored name"

        @JvmStatic
        fun probes() = listOf(MangaFeedRegistryProbe(), NovelFeedRegistryProbe())
    }
}

// The model works on the IO dispatcher, so the wait runs in real time rather than the test clock.
private suspend fun <T> settle(block: suspend () -> T): T =
    withContext(Dispatchers.Default) { withTimeout(5.seconds) { block() } }

/** Both registries over lists the test can change, with one type's source to install. */
interface FeedRegistryProbe {
    val key: SourceKey
    val otherKey: SourceKey
    val otherType: ContentType get() = otherKey.contentType
    val enabledManga: List<DomainSource> get() = emptyList()
    val enabledNovels: List<NovelSource> get() = emptyList()
    val mangaRegistry: SourceManager
    val novelRegistry: NovelSourceManager
    fun install()
}

class MangaFeedRegistryProbe : FeedRegistryProbe {
    private val installed = MutableStateFlow(emptyList<MangaSource>())
    private val source = mockk<MangaSource>(relaxed = true) {
        every { id } returns 1L
        every { name } returns FeedViewModelTest.SOURCE_NAME
    }

    override fun toString() = "manga"
    override val enabledManga get() = installed.value.map { source ->
        mockk<DomainSource>(relaxed = true) {
            every { id } returns source.id
            every { isUsedLast } returns false
        }
    }
    override val key = SourceKey.Manga(1L)
    override val otherKey = SourceKey.Novel("other")
    override val mangaRegistry = mockk<SourceManager> {
        every { sources } returns installed
        coEvery { this@mockk.get(any<Long>()) } answers { installed.value.firstOrNull { it.id == firstArg<Long>() } }
        coEvery { getOrStub(1L) } returns StubSource(id = 1L, lang = "en", name = FeedViewModelTest.STORED_NAME)
    }
    override val novelRegistry = emptyNovelRegistry()
    override fun install() {
        installed.value = listOf(source)
    }
}

class NovelFeedRegistryProbe : FeedRegistryProbe {
    private val installed = MutableStateFlow(emptyList<NovelSource>())
    private val source = mockk<NovelSource>(relaxed = true) {
        every { id } returns "plugin"
        every { name } returns FeedViewModelTest.SOURCE_NAME
    }

    override fun toString() = "novels"
    override val enabledNovels get() = installed.value
    override val key = SourceKey.Novel("plugin")
    override val otherKey = SourceKey.Manga(2L)
    override val mangaRegistry = mockk<SourceManager> { every { sources } returns MutableStateFlow(emptyList()) }
    override val novelRegistry: NovelSourceManager = mockk {
        every { loadedSources() } returns installed
    }

    init {
        // Stubbed off the mock: `get` inside a mockk block binds to MockK's own dynamic-call helper.
        coEvery { novelRegistry.get(any()) } answers { installed.value.firstOrNull { it.id == firstArg<String>() } }
        coEvery { novelRegistry.nameOf("plugin") } returns FeedViewModelTest.STORED_NAME
    }

    override fun install() {
        installed.value = listOf(source)
    }
}

private fun emptyNovelRegistry() = mockk<NovelSourceManager> {
    every { loadedSources() } returns MutableStateFlow(emptyList())
}
