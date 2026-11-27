package reikai.presentation.browse.extension

import androidx.lifecycle.viewModelScope
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.source.NovelExtensionFormat
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import kotlin.time.Duration.Companion.seconds

/** One install pipeline can serve both content types, and the chip still shows only its own. */
class ExtensionsEngineTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private val apks = setOf(ContentType.MANGA, ContentType.NOVELS)

    @Test
    fun `the Novels chip shows only the novel rows of a pipeline serving both`() = runTest(dispatcher) {
        val state = settledState(
            ContentType.NOVELS,
            FakeProvider(
                apks,
                listOf(row(ExtensionKey.Manga("m")), row(ExtensionKey.NovelApk("n", NovelExtensionFormat.APK))),
            ),
        )

        state.rows().map { it.key } shouldBe listOf(ExtensionKey.NovelApk("n", NovelExtensionFormat.APK))
    }

    @Test
    fun `a store with only manga is no repo under the Novels chip`() = runTest(dispatcher) {
        val state =
            settledState(ContentType.NOVELS, FakeProvider(apks, emptyList(), reposFor = setOf(ContentType.MANGA)))

        state.hasRepos shouldBe false
    }

    @Test
    fun `no install permission is asked for where no shown row installs through the system`() = runTest(dispatcher) {
        val state = settledState(
            ContentType.NOVELS,
            FakeProvider(apks, listOf(row(ExtensionKey.Manga("m"))), needsInstallPermission = true),
            FakeProvider(setOf(ContentType.NOVELS), listOf(row(ExtensionKey.Novel("plugin")))),
        )

        state.needsInstallPermission shouldBe false
    }

    @Test
    fun `the install permission is asked for where a shown row installs through the system`() = runTest(dispatcher) {
        val state = settledState(
            ContentType.MANGA,
            FakeProvider(apks, listOf(row(ExtensionKey.Manga("m"))), needsInstallPermission = true),
            FakeProvider(setOf(ContentType.NOVELS), listOf(row(ExtensionKey.Novel("plugin")))),
        )

        state.needsInstallPermission shouldBe true
    }

    @Test
    fun `novel rows packaged two ways name their format`() = runTest(dispatcher) {
        val state = settledState(
            ContentType.NOVELS,
            FakeProvider(apks, listOf(row(ExtensionKey.NovelApk("n", NovelExtensionFormat.APK)))),
            FakeProvider(setOf(ContentType.NOVELS), listOf(row(ExtensionKey.Novel("plugin")))),
        )

        state.showsFormat shouldBe true
    }

    @Test
    fun `novel rows packaged one way stay as they always looked`() = runTest(dispatcher) {
        val state = settledState(
            ContentType.ALL,
            FakeProvider(apks, listOf(row(ExtensionKey.Manga("m")))),
            FakeProvider(setOf(ContentType.NOVELS), listOf(row(ExtensionKey.Novel("plugin")))),
        )

        state.showsFormat shouldBe false
    }

    @Test
    fun `the All chip counts pending updates per content type`() = runTest(dispatcher) {
        val state = settledState(
            ContentType.ALL,
            FakeProvider(apks, listOf(update(ExtensionKey.Manga("m")), row(ExtensionKey.Manga("installed")))),
            FakeProvider(
                setOf(ContentType.NOVELS),
                listOf(update(ExtensionKey.Novel("a")), update(ExtensionKey.Novel("b"))),
            ),
        )

        state.updatesByType shouldBe mapOf(ContentType.MANGA to 1, ContentType.NOVELS to 2)
    }

    @Test
    fun `a single type's chip counts no pending updates per type`() = runTest(dispatcher) {
        val state = settledState(
            ContentType.NOVELS,
            FakeProvider(setOf(ContentType.NOVELS), listOf(update(ExtensionKey.Novel("a")))),
        )

        state.updatesByType shouldBe emptyMap()
    }

    /** A screen reopened after its list stopped, say back from adding a repo, must not show "No repos yet" first. */
    @Test
    fun `a list reopened after it stopped never replays its empty state from before`() = runTest(dispatcher) {
        val rows = MutableStateFlow(emptyList<BrowseExtensionRow>())
        val repos = MutableStateFlow(emptySet<ContentType>())
        val preferences = mockk<ReikaiSourcePreferences> {
            every { browseContentType.changes() } returns MutableStateFlow(ContentType.ALL)
        }
        virtualDispatchers(dispatcher)
        try {
            val engine =
                ExtensionsEngine(listOf(ChangingProvider(rows, repos)), MutableStateFlow<String?>(null), preferences)
            val screen = launch { engine.state.collect {} }
            advanceUntilIdle()
            check(engine.state.value.isEmpty && !engine.state.value.hasRepos)
            screen.cancel()
            advanceTimeBy(6.seconds)
            rows.value = listOf(row(ExtensionKey.Manga("m")))
            repos.value = setOf(ContentType.MANGA)

            engine.state.first().isEmpty shouldBe false
            engine.viewModelScope.coroutineContext.job.cancelAndJoin()
        } finally {
            resetVirtualDispatchers()
        }
    }

    private suspend fun TestScope.settledState(
        chip: ContentType,
        vararg providers: ExtensionsProvider,
    ): ExtensionsEngine.State {
        val preferences = mockk<ReikaiSourcePreferences> {
            every { browseContentType.changes() } returns MutableStateFlow(chip)
        }
        val engine = ExtensionsEngine(providers.toList(), MutableStateFlow<String?>(null), preferences)
        backgroundScope.launch { engine.state.collect {} }
        val state = engine.state.first { !it.isLoading && it.contentType == chip }
        // Cleared as a screen would clear it, while the test dispatcher is still Main: its scope works on
        // IO and resumes on Main, so anything left running lands on the real Main after this test.
        engine.viewModelScope.coroutineContext.job.cancelAndJoin()
        return state
    }

    private fun ExtensionsEngine.State.rows() = items.filterIsInstance<ExtensionsListItem.Row>().map { it.row }

    private fun update(key: ExtensionKey) = row(key, ExtensionSection.Updates)

    private fun row(key: ExtensionKey, section: ExtensionSection = ExtensionSection.Installed) = BrowseExtensionRow(
        key = key,
        name = key.toString(),
        lang = "en",
        section = section,
        needsAttention = false,
        searchTerms = emptyList(),
        searchIds = emptyList(),
        payload = Unit,
    )

    private class FakeProvider(
        override val contentTypes: Set<ContentType>,
        rows: List<BrowseExtensionRow>,
        reposFor: Set<ContentType> = contentTypes,
        needsInstallPermission: Boolean = false,
    ) : ExtensionsProvider {
        override val rows = flowOf(rows)
        override val reposFor = flowOf(reposFor)
        override val isRefreshing = flowOf(false)
        override val needsInstallPermission = flowOf(needsInstallPermission)
        override fun refresh() = Unit
        override fun updateAll(rows: List<BrowseExtensionRow>) = Unit
    }

    private class ChangingProvider(
        override val rows: Flow<List<BrowseExtensionRow>?>,
        override val reposFor: Flow<Set<ContentType>>,
    ) : ExtensionsProvider {
        override val contentTypes = setOf(ContentType.MANGA)
        override val isRefreshing = flowOf(false)
        override val needsInstallPermission = flowOf(false)
        override fun refresh() = Unit
        override fun updateAll(rows: List<BrowseExtensionRow>) = Unit
    }
}
