package eu.kanade.tachiyomi.extension

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.repository.ExtensionStoreRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.extension.RepoStatus
import reikai.domain.library.ContentType
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import java.io.IOException

/** Listing fetches can overlap (a screen opening, a refresh, a store just added), and the newest must stand. */
@RunWith(RobolectricTestRunner::class)
class ExtensionListingFetchTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val answers = ArrayDeque<CompletableDeferred<Map<String, Result<List<Extension.Available>>>>>()
    private val repository = mockk<ExtensionStoreRepository>(relaxed = true) {
        coEvery { getAll() } returns emptyList()
        every { getAllAsFlow() } returns flowOf(emptyList())
        coEvery { fetchExtensionsByStore() } coAnswers { answers.removeFirst().await() }
    }

    @Before
    fun setUp() {
        mockkObject(ExtensionLoader)
        coEvery { ExtensionLoader.loadExtensions(any(), any()) } returns emptyList()
        virtualDispatchers(dispatcher)
    }

    @After
    fun tearDown() {
        scope.cancel()
        resetVirtualDispatchers()
        unmockkObject(ExtensionLoader)
    }

    @Test
    fun `a fetch finishing after a newer one leaves the newer listing`() = runTest(dispatcher) {
        val manager = manager()
        val older = CompletableDeferred<Map<String, Result<List<Extension.Available>>>>().also(answers::add)
        val newer = CompletableDeferred<Map<String, Result<List<Extension.Available>>>>().also(answers::add)

        launch { manager.findAvailableExtensions() }
        runCurrent()
        launch { manager.findAvailableExtensions() }
        runCurrent()
        newer.complete(mapOf(URL to Result.success(emptyList())))
        runCurrent()
        older.complete(mapOf(URL to Result.failure(IOException("down"))))
        advanceUntilIdle()

        manager.storeStatuses.value?.get(URL) shouldBe RepoStatus.Reached(manga = 0, novels = 0)
    }

    private fun manager() = ExtensionManager(
        scope = scope,
        context = ApplicationProvider.getApplicationContext<Application>(),
        preferences = mockk(relaxed = true) {
            every { enabledContentWarnings.get() } returns emptySet()
            every { applyContentWarningsToInstalled.get() } returns false
            every { refusedExtensionStores.get() } returns emptySet()
        },
        trustExtension = mockk(relaxed = true),
        exhPreferences = mockk(relaxed = true),
        extensionStoreRepository = repository,
        updateExtensionStores = mockk(relaxed = true),
        installer = mockk(relaxed = true),
        extensionUpdateNotifier = mockk(relaxed = true),
        novelPreferences = mockk(relaxed = true),
        reikaiLibraryPreferences = mockk { every { shownContent.get() } returns ContentType.ALL },
    )

    private companion object {
        const val URL = "https://example.org/index.min.json"
    }
}
