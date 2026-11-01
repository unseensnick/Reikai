package reikai.domain.extension

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore

/** Every Browse badge counts both kinds of novel extension, the plugins and the apks. */
class ExtensionUpdateCountsTest {

    private val store = EmittingPreferenceStore()
    private val libraryPreferences = ReikaiLibraryPreferences(store)

    private val counts = ExtensionUpdateCounts(
        sourcePreferences = mockk<SourcePreferences> {
            every { extensionUpdatesCount.changes() } returns MutableStateFlow(1)
        },
        novelPreferences = NovelPreferences(EmittingPreferenceStore()).apply {
            pendingPluginUpdates().set(setOf("a", "b"))
        },
        extensionManager = mockk<ExtensionManager> {
            every { novelUpdatesCount } returns MutableStateFlow(4)
        },
        reikaiLibraryPreferences = libraryPreferences,
    )

    @Test
    fun `novels count the plugins and the apks`() = runTest {
        counts.novel.first() shouldBe 6
    }

    @Test
    fun `the total counts every kind`() = runTest {
        counts.total.first() shouldBe 7
    }

    @Test
    fun `a hidden type's updates leave the badges`() = runTest {
        libraryPreferences.shownContent.set(ContentType.NOVELS)

        counts.total.first() shouldBe 6
    }

    @Test
    fun `hiding novels leaves the manga count`() = runTest {
        libraryPreferences.shownContent.set(ContentType.MANGA)

        counts.total.first() shouldBe 1
    }
}
