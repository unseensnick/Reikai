package eu.kanade.tachiyomi.extension

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mihon.domain.extension.model.ExtensionStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import reikai.domain.extension.RefusedStore
import reikai.domain.extension.RepoStatus
import reikai.domain.extension.StoreRefresh
import reikai.domain.library.ContentType
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * A store whose address now serves another key stays refused across restarts, through the saved refusal,
 * and the user is told about it once rather than on every daily check.
 */
@RunWith(RobolectricTestRunner::class)
class ExtensionStoreRefusalTest {

    private val scope = CoroutineScope(
        UnconfinedTestDispatcher() + SupervisorJob() + CoroutineExceptionHandler { _, _ -> },
    )
    private val refused = InMemoryPreferenceStore().getStringSet("refused", emptySet())
    private val refusal = RefusedStore(URL, "Store", servedKey = "other", servedName = "Other")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(ExtensionLoader)
        coEvery { ExtensionLoader.loadExtensions(any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        scope.cancel()
        unmockkObject(ExtensionLoader)
        Dispatchers.resetMain()
    }

    @Test
    fun `a refusal is told once across daily checks`() = runTest {
        val manager = manager(savedKey = "key")

        repeat(2) { manager.refreshStores() }
        ShadowLooper.idleMainLooper()

        ShadowToast.shownToastCount() shouldBe 1
    }

    @Test
    fun `a refresh that finds the store's own key again lists it again`() = runTest {
        val manager = manager(savedKey = "key", refreshes = listOf(refused(), StoreRefresh(emptyList(), setOf(URL))))

        repeat(2) { manager.refreshStores() }
        manager.findAvailableExtensions()

        manager.storeStatuses.value?.get(URL) shouldBe RepoStatus.Reached(manga = 0, novels = 0)
    }

    /** A list-url or legacy store's listing is refused by the saved refusal alone. */
    @Test
    fun `a refresh that cannot reach a refused store keeps its listing refused`() = runTest {
        val manager = manager(savedKey = "key", refreshes = listOf(refused(), StoreRefresh(emptyList(), emptySet())))

        repeat(2) { manager.refreshStores() }
        manager.findAvailableExtensions()

        manager.storeStatuses.value?.get(URL) shouldBe RepoStatus.KeyChanged("Other")
    }

    @Test
    fun `a refused store's listing reads as refused after a restart`() = runTest {
        refused.set(setOf(refusal.encode()))
        val manager = manager(savedKey = "key")

        manager.findAvailableExtensions()

        manager.storeStatuses.value?.get(URL) shouldBe RepoStatus.KeyChanged("Other")
    }

    @Test
    fun `a store added again under the new key lists again`() = runTest {
        refused.set(setOf(refusal.encode()))
        val manager = manager(savedKey = "other")

        manager.findAvailableExtensions()

        manager.storeStatuses.value?.get(URL) shouldBe RepoStatus.Reached(manga = 0, novels = 0)
    }

    private fun refused() = StoreRefresh(listOf(refusal), reached = setOf(URL))

    private fun manager(savedKey: String, refreshes: List<StoreRefresh> = listOf(refused())): ExtensionManager {
        val saved = ExtensionStore(
            indexUrl = URL,
            name = "Store",
            badgeLabel = "Store",
            signingKey = savedKey,
            contact = ExtensionStore.Contact(website = "https://example.org", discord = null),
            isLegacy = false,
            extensionListUrl = null,
        )
        return ExtensionManager(
            scope = scope,
            context = ApplicationProvider.getApplicationContext<Application>(),
            preferences = mockk(relaxed = true) {
                every { enabledContentWarnings.get() } returns emptySet()
                every { applyContentWarningsToInstalled.get() } returns false
                every { refusedExtensionStores } returns refused
            },
            trustExtension = mockk(relaxed = true),
            exhPreferences = mockk(relaxed = true),
            extensionStoreRepository = mockk(relaxed = true) {
                coEvery { getAll() } returns listOf(saved)
                every { getAllAsFlow() } returns flowOf(listOf(saved))
                coEvery { fetchExtensionsByStore() } returns mapOf(URL to Result.success(emptyList()))
            },
            updateExtensionStores = mockk { coEvery { this@mockk.invoke() } returnsMany refreshes },
            installer = mockk(relaxed = true),
            extensionUpdateNotifier = mockk(relaxed = true),
            novelPreferences = mockk(relaxed = true),
            reikaiLibraryPreferences = mockk { every { shownContent.get() } returns ContentType.ALL },
        )
    }

    private companion object {
        const val URL = "https://example.org/index.min.json"
    }
}
