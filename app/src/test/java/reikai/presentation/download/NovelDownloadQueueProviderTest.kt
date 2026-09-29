package reikai.presentation.download

import eu.kanade.tachiyomi.extension.ExtensionManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.download.SeriesCompletions
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSourceManager

class NovelDownloadQueueProviderTest {

    @Test
    fun `a queued novel from a removed plugin shows the plugin's last name`() = runTest {
        val provider = NovelDownloadQueueProvider(
            downloadManager = mockk<NovelDownloadManager> {
                every { queueState } returns
                    MutableStateFlow(listOf(NovelDownload(novelId = 1L, chapterId = 2L, url = "/c")))
                every { downloadingNovelId } returns MutableStateFlow(null)
                every { completions } returns SeriesCompletions()
            },
            novelRepo = mockk { coEvery { getById(1L) } returns Novel.create().copy(id = 1L, source = "gone") },
            chapterRepo = mockk(),
            sourceManager = NovelSourceManager(
                installer = { mockk(relaxed = true) },
                extensionManager = mockk<ExtensionManager> {
                    every { loadedNovelExtensionsFlow } returns MutableStateFlow(emptyList())
                },
                prefs = mockk<NovelPreferences> {
                    every { seenNovelSources() } returns mockk(relaxed = true) {
                        every { get() } returns mapOf("gone" to LnSourceIdentity("Removed plugin"))
                    }
                },
            ),
        )

        provider.snapshots.first().labels.getValue(1L).sourceName shouldBe "Removed plugin"
    }
}
