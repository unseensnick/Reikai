package reikai.novel.source

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.novel.install.LnPluginInstaller
import reikai.novel.install.LnPluginLoadFailure
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class NovelSourceLoadFailureTest {

    @Test
    fun `a chapter of a plugin that failed to load names why`() = runTest {
        val failure = LnPluginLoadFailure(
            url = "https://repo.test/plugin.js",
            pluginId = "plugin",
            name = "Plugin",
            iconUrl = null,
            lang = null,
            version = null,
            customCssUrl = null,
            reason = LnPluginLoadFailure.Reason.Failed(message = "script threw at line 3", stackTrace = ""),
        )
        val installer = mockk<LnPluginInstaller>(relaxed = true) {
            every { failures } returns MutableStateFlow(mapOf(failure.url to failure))
        }
        val loader = NovelChapterTextLoader(
            context = mockk(relaxed = true),
            novelRepo = mockk { coEvery { getById(any()) } returns Novel.create().copy(id = 1L, source = "plugin") },
            sourceManager = mockk<NovelSourceManager>(relaxed = true).also { coEvery { it.get(any()) } returns null },
            installer = installer,
            preferences = NovelPreferences(InMemoryPreferenceStore()),
            textCache = testChapterTextCache(testChapterCache()),
            readDownloaded = { _, _ -> null },
        )

        val error = shouldThrow<IllegalStateException> { loader.resolveSource(1L) }

        error.message.orEmpty() shouldContain "script threw at line 3"
    }
}
