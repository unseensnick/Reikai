package reikai.presentation.browse.repos

import androidx.lifecycle.viewModelScope
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore
import kotlin.time.Duration.Companion.seconds

class RepositoriesViewModelTest {

    private val store = EmittingPreferenceStore()
    private val library = ReikaiLibraryPreferences(store)
    private var model: RepositoriesViewModel? = null

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() {
        model?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    private fun model(): RepositoriesViewModel {
        val novels = NovelPreferences(store).also { it.addedRepoUrls().set(setOf(PLUGIN_REPO)) }
        return RepositoriesViewModel(
            getExtensionStores = mockk { every { subscribe() } returns flowOf(emptyList()) },
            addExtensionStore = mockk(),
            removeExtensionStore = mockk(),
            updateExtensionStores = mockk(),
            extensionManager = mockk { every { storeStatuses } returns MutableStateFlow(emptyMap()) },
            registries = mockk {
                every { results } returns flowOf(emptyMap())
                every { isRefreshing } returns MutableStateFlow(false)
            },
            prefs = novels,
            reikaiLibraryPreferences = library,
        ).also { model = it }
    }

    private suspend fun settled() = withContext(Dispatchers.Default) {
        withTimeout(5.seconds) { model().state.first { !it.isLoading } }
    }

    @Test
    fun `a repo Show keeps out of the list is still counted, so an add does not just vanish`() = runTest {
        library.shownContent.set(ContentType.MANGA)

        val state = settled()

        listOf(state.cards.size, state.hiddenCount) shouldBe listOf(0, 1)
    }

    @Test
    fun `the Add dialog still knows a repo Show keeps out of the list`() = runTest {
        library.shownContent.set(ContentType.MANGA)

        settled().addedAddresses shouldBe setOf(PLUGIN_REPO)
    }

    @Test
    fun `nothing is hidden while both types show`() = runTest {
        val state = settled()

        listOf(state.cards.size, state.hiddenCount) shouldBe listOf(1, 0)
    }

    private companion object {
        const val PLUGIN_REPO = "https://example.org/plugins.json"
    }
}
