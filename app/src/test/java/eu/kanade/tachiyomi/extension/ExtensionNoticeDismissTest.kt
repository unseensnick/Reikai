package eu.kanade.tachiyomi.extension

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.kanade.tachiyomi.extension.api.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import mihon.domain.extension.model.ContentWarning
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.library.ContentType

/** The notice lists only what Show keeps, so a hidden type's pending update must not hold it open. */
@RunWith(RobolectricTestRunner::class)
class ExtensionNoticeDismissTest {

    private val notifier = mockk<ExtensionUpdateNotifier>(relaxed = true)
    private val scope = CoroutineScope(
        UnconfinedTestDispatcher() + SupervisorJob() + CoroutineExceptionHandler { _, _ -> },
    )

    @Before
    fun setUp() = mockkObject(ExtensionLoader)

    @After
    fun tearDown() {
        scope.cancel()
        unmockkObject(ExtensionLoader)
    }

    private fun extension(kind: Extension.Kind, hasUpdate: Boolean) = Extension.NotLoaded(
        name = kind.name,
        pkgName = kind.name,
        versionName = "1",
        versionCode = 1L,
        isShared = false,
        contentWarning = ContentWarning.SAFE,
        signatures = emptyList(),
        kind = kind,
        hasUpdate = hasUpdate,
        reason = Extension.NotLoaded.Reason.Untrusted("key"),
    )

    private fun manager(shown: ContentType): ExtensionManager {
        coEvery { ExtensionLoader.loadExtensions(any(), any()) } returns listOf(
            extension(Extension.Kind.MANGA, hasUpdate = false),
            extension(Extension.Kind.TACHIYOMI_NOVEL, hasUpdate = true),
        )
        return ExtensionManager(
            scope = scope,
            context = ApplicationProvider.getApplicationContext<Application>(),
            preferences = mockk(relaxed = true) {
                every { enabledContentWarnings.get() } returns emptySet()
                every { applyContentWarningsToInstalled.get() } returns false
            },
            trustExtension = mockk(relaxed = true),
            exhPreferences = mockk(relaxed = true),
            extensionStoreRepository = mockk(relaxed = true) {
                coEvery { getAll() } returns emptyList()
                every { getAllAsFlow() } returns flowOf(emptyList())
            },
            updateExtensionStores = mockk(relaxed = true),
            installer = mockk(relaxed = true),
            extensionUpdateNotifier = notifier,
            novelPreferences = mockk(relaxed = true),
            reikaiLibraryPreferences = mockk {
                every { shownContent.get() } returns shown
            },
        )
    }

    @Test
    fun `a hidden type's pending update does not hold the notice open`() {
        manager(ContentType.MANGA)

        verify(timeout = 5_000) { notifier.dismiss() }
    }

    @Test
    fun `a shown type's pending update holds the notice open`() {
        manager(ContentType.ALL)

        verify(timeout = 1_000, exactly = 0) { notifier.dismiss() }
    }
}
