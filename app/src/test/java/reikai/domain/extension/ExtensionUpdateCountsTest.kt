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
import reikai.domain.novel.NovelPreferences

/** Every Browse badge counts both kinds of novel extension, the plugins and the apks. */
class ExtensionUpdateCountsTest {

    private val counts = ExtensionUpdateCounts(
        sourcePreferences = mockk<SourcePreferences> {
            every { extensionUpdatesCount.changes() } returns MutableStateFlow(1)
        },
        novelPreferences = mockk<NovelPreferences> {
            every { pluginUpdatesCount().changes() } returns MutableStateFlow(2)
        },
        extensionManager = mockk<ExtensionManager> {
            every { novelUpdatesCount } returns MutableStateFlow(4)
        },
    )

    @Test
    fun `novels count the plugins and the apks`() = runTest {
        counts.novel.first() shouldBe 6
    }

    @Test
    fun `the total counts every kind`() = runTest {
        counts.total.first() shouldBe 7
    }
}
