package reikai.presentation.browse.feed

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.FavoritedNovels
import reikai.domain.source.ReikaiSourcePreferences
import reikai.domain.source.SourceKey
import reikai.domain.source.model.FeedSavedSearch
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.globalsearch.EntrySearchState
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.seconds
import eu.kanade.tachiyomi.source.Source as MangaSource

/**
 * A feed row follows its own type's source registry: a row built while its source was missing reads
 * unavailable, and installing the source afterwards fills it without leaving Browse, for both types.
 */
class FeedViewModelTest {

    // viewModelScope is Main-based, so the model needs a Main the test controls.
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a row whose source is installed after the feed was built fills in`(probe: FeedRegistryProbe) = runTest {
        val model = feedViewModel(probe)
        settle { model.state.first { it.loaded } }

        probe.install()

        settle { model.state.first { it.entries.single().row.state !is EntrySearchState.Unavailable } }
            .entries.single().sourceName shouldBe SOURCE_NAME
    }

    private fun feedViewModel(probe: FeedRegistryProbe) = FeedViewModel(
        feedRepository = mockk {
            every { subscribeGlobal() } returns flowOf(listOf(FeedSavedSearch(1L, probe.key, null, true, 0L)))
        },
        savedSearchRepository = mockk { coEvery { getAll() } returns emptyList() },
        preferences = ReikaiSourcePreferences(EmittingPreferenceStore()),
        novelRepository = mockk { every { getFavoritedKeysAsFlow() } returns flowOf(FavoritedNovels.None) },
        getManga = mockk(relaxed = true),
        mangaAdder = mockk(relaxed = true),
        novelAdder = mockk(relaxed = true),
        sourceManager = probe.mangaRegistry,
        getEnabledSources = mockk(relaxed = true),
        networkToLocalManga = mockk(relaxed = true),
        novelSourceManager = probe.novelRegistry,
        getEnabledNovelSources = mockk(relaxed = true),
    )

    companion object {
        const val SOURCE_NAME = "Installed later"

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
    override val key = SourceKey.Manga(1L)
    override val mangaRegistry = mockk<SourceManager> {
        every { sources } returns installed
        coEvery { this@mockk.get(any<Long>()) } answers { installed.value.firstOrNull { it.id == firstArg<Long>() } }
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
    override val key = SourceKey.Novel("plugin")
    override val mangaRegistry = mockk<SourceManager> { every { sources } returns MutableStateFlow(emptyList()) }
    override val novelRegistry: NovelSourceManager = mockk {
        every { loadedSources() } returns installed
    }

    init {
        // Stubbed off the mock: `get` inside a mockk block binds to MockK's own dynamic-call helper.
        coEvery { novelRegistry.get(any()) } answers { installed.value.firstOrNull { it.id == firstArg<String>() } }
    }

    override fun install() {
        installed.value = listOf(source)
    }
}

private fun emptyNovelRegistry() = mockk<NovelSourceManager> {
    every { loadedSources() } returns MutableStateFlow(emptyList())
}
