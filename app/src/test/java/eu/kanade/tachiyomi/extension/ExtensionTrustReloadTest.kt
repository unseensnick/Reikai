package eu.kanade.tachiyomi.extension

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import mihon.domain.extension.model.ExtensionStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.Preference

/** A store added while the first installed scan runs is already current when the trust check subscribes. */
@RunWith(RobolectricTestRunner::class)
class ExtensionTrustReloadTest {

    private val scope = CoroutineScope(
        UnconfinedTestDispatcher() + SupervisorJob() + CoroutineExceptionHandler { _, _ -> },
    )
    private val store = ExtensionStore(
        indexUrl = "https://repo/index.min.json",
        name = "Repo",
        badgeLabel = "",
        signingKey = "key",
        contact = ExtensionStore.Contact(website = "", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    @Before
    fun setUp() {
        mockkObject(ExtensionLoader)
        coEvery { ExtensionLoader.loadExtensions(any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        scope.cancel()
        unmockkObject(ExtensionLoader)
    }

    @Test
    fun `a store added during the first scan is scanned again`() {
        val trusted: Preference<Set<String>> = EmittingPreferenceStore().getStringSet("trusted", emptySet())
        ExtensionManager(
            scope = scope,
            context = ApplicationProvider.getApplicationContext<Application>(),
            preferences = mockk(relaxed = true) {
                every { enabledContentWarnings.get() } returns emptySet()
                every { applyContentWarningsToInstalled.get() } returns false
                every { trustedExtensions } returns trusted
            },
            trustExtension = mockk(relaxed = true),
            exhPreferences = mockk(relaxed = true),
            extensionStoreRepository = mockk(relaxed = true) {
                // The first scan reads no stores; by the time it lands the store has been added.
                coEvery { getAll() } returnsMany listOf(emptyList(), listOf(store))
                every { getAllAsFlow() } returns MutableStateFlow(listOf(store))
            },
            updateExtensionStores = mockk(relaxed = true),
            installer = mockk(relaxed = true),
            extensionUpdateNotifier = mockk(relaxed = true),
            novelPreferences = mockk(relaxed = true),
            reikaiLibraryPreferences = mockk(relaxed = true),
        )

        coVerify(timeout = 5_000, atLeast = 2) { ExtensionLoader.loadExtensions(any(), any()) }
    }
}
