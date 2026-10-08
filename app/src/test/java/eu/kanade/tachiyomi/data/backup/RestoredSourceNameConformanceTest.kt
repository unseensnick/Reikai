package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSource
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.extension.ExtensionManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.novel.source.NovelSourceManager
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager

/**
 * A restored entry whose source is not installed keeps the name the backup recorded for that source, so
 * the next backup writes it again and the entry does not show a bare id. Manga keep it as a stub source,
 * novels as the record their sources leave once removed.
 */
class RestoredSourceNameConformanceTest {

    private val options = RestoreOptions(libraryEntries = true, categories = false, appSettings = false)
    private val noSourceInstalled = mockk<SourceManager>(relaxed = true).also { coEvery { it.get(any()) } returns null }

    @Test
    fun `a manga source that is not installed keeps its backed-up name`() = runTest {
        val stubs = mockk<StubSourceRepository>(relaxed = true) {
            coEvery { getStubSource(any()) } returns null
        }

        restoreEncoded(
            Backup(backupManga = emptyList(), backupSources = listOf(BackupSource(name = "Gone", sourceId = 5L))),
            options,
            sourceManager = noSourceInstalled,
            stubSourceRepository = stubs,
        )

        coVerify { stubs.upsertStubSource(5L, "", "Gone") }
    }

    @Test
    fun `a manga source with a stub already keeps the stub`() = runTest {
        val stubs = mockk<StubSourceRepository>(relaxed = true) {
            coEvery { getStubSource(5L) } returns StubSource(5L, "en", "Stored")
        }

        restoreEncoded(
            Backup(backupManga = emptyList(), backupSources = listOf(BackupSource(name = "Gone", sourceId = 5L))),
            options,
            sourceManager = noSourceInstalled,
            stubSourceRepository = stubs,
        )

        coVerify(exactly = 0) { stubs.upsertStubSource(any(), any(), any()) }
    }

    @Test
    fun `a novel source that is not installed keeps its backed-up name`() = runTest {
        val novelSources = novelSourceManager(NovelPreferences(EmittingPreferenceStore()))

        restoreEncoded(
            Backup(backupManga = emptyList(), backupNovelSources = listOf(BackupNovelSource("Gone Novels", "gone"))),
            options,
            novelSourceManager = novelSources,
        )

        novelSources.identityOf("gone").name shouldBe "Gone Novels"
    }

    @Test
    fun `a novel source this device already knows keeps what it knows`() = runTest {
        val prefs = NovelPreferences(EmittingPreferenceStore())
        prefs.seenNovelSources().set(mapOf("seen" to LnSourceIdentity(name = "Seen", iconUrl = "icon")))
        val novelSources = novelSourceManager(prefs)

        restoreEncoded(
            Backup(backupManga = emptyList(), backupNovelSources = listOf(BackupNovelSource("Old name", "seen"))),
            options,
            novelSourceManager = novelSources,
        )

        novelSources.identityOf("seen") shouldBe LnSourceIdentity(name = "Seen", iconUrl = "icon")
    }

    private fun novelSourceManager(prefs: NovelPreferences) = NovelSourceManager(
        installer = { error("loaded the plugins") },
        extensionManager = mockk<ExtensionManager> { every { loadedNovelExtensionsFlow } returns flowOf(emptyList()) },
        prefs = prefs,
    )
}
