package reikai.presentation.browse.extension

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.source.ReikaiSourcePreferences

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
            FakeProvider(apks, listOf(row(ExtensionKey.Manga("m")), row(ExtensionKey.NovelApk("n")))),
        )

        state.rows().map { it.key } shouldBe listOf(ExtensionKey.NovelApk("n"))
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
    fun `novel rows packaged two ways name their format`() = runTest(dispatcher) {
        val state = settledState(
            ContentType.NOVELS,
            FakeProvider(apks, listOf(row(ExtensionKey.NovelApk("n")))),
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

    private suspend fun TestScope.settledState(
        chip: ContentType,
        vararg providers: ExtensionsProvider,
    ): ExtensionsEngine.State {
        val preferences = mockk<ReikaiSourcePreferences> {
            every { browseContentType.changes() } returns MutableStateFlow(chip)
        }
        val engine = ExtensionsEngine(providers.toList(), MutableStateFlow<String?>(null), preferences)
        backgroundScope.launch { engine.state.collect {} }
        return engine.state.first { !it.isLoading && it.contentType == chip }
    }

    private fun ExtensionsEngine.State.rows() = items.filterIsInstance<ExtensionsListItem.Row>().map { it.row }

    private fun row(key: ExtensionKey) = BrowseExtensionRow(
        key = key,
        name = key.toString(),
        lang = "en",
        section = ExtensionSection.Installed,
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
}
