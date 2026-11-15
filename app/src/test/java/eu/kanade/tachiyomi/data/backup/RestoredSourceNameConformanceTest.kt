package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSource
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.extension.ExtensionManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.novel.source.NovelSourceManager
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.testAppScope
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager

/**
 * A restored entry whose source is not installed keeps the name the backup recorded for that source, so
 * the next backup writes it again and the entry does not show a bare id. Manga keep it as a stub source,
 * novels as the record their sources leave once removed. Runs the real BackupRestorer for each type.
 */
class RestoredSourceNameConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `a source that is not installed keeps its backed-up name`(type: SourceNames) = runTest {
        type.nameAfterRestore(backedUp = "Gone", onDevice = null) shouldBe "Gone"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `a source this device already has a name for keeps it`(type: SourceNames) = runTest {
        type.nameAfterRestore(backedUp = "Gone", onDevice = "Stored") shouldBe "Stored"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `a blank backed-up name records nothing`(type: SourceNames) = runTest {
        type.nameAfterRestore(backedUp = " ", onDevice = null) shouldBe null
    }

    // Manga only: an installed novel source answers its own name, so it needs no record to name it.
    @Test
    fun `an installed manga source gets no stub`() = runTest {
        MangaSourceNames.nameAfterRestore(backedUp = "Gone", onDevice = null, installed = true) shouldBe null
    }

    companion object {
        @JvmStatic
        fun types() = listOf(MangaSourceNames, NovelSourceNames)
    }
}

private val OPTIONS = RestoreOptions(libraryEntries = true, categories = false, appSettings = false)

/** One type's restore of one source's backed-up name, returning the name the device then records for it. */
interface SourceNames {
    suspend fun nameAfterRestore(backedUp: String, onDevice: String?): String?
}

object MangaSourceNames : SourceNames {

    override fun toString() = "manga"

    override suspend fun nameAfterRestore(backedUp: String, onDevice: String?) =
        nameAfterRestore(backedUp, onDevice, installed = false)

    suspend fun nameAfterRestore(backedUp: String, onDevice: String?, installed: Boolean): String? {
        var stub = onDevice?.let { StubSource(5L, "en", it) }
        val stubs = mockk<StubSourceRepository>(relaxed = true) {
            coEvery { getStubSource(5L) } answers { stub }
            coEvery { upsertStubSource(5L, any(), any()) } answers { stub = StubSource(5L, secondArg(), thirdArg()) }
        }
        val sources = mockk<SourceManager>(relaxed = true) {
            coEvery { get(5L) } returns if (installed) mockk() else null
        }
        restoreEncoded(
            Backup(backupManga = emptyList(), backupSources = listOf(BackupSource(name = backedUp, sourceId = 5L))),
            OPTIONS,
            sourceManager = sources,
            stubSourceRepository = stubs,
        )
        return stub?.name
    }
}

object NovelSourceNames : SourceNames {

    override fun toString() = "novel"

    override suspend fun nameAfterRestore(backedUp: String, onDevice: String?): String? {
        val prefs = NovelPreferences(EmittingPreferenceStore())
        onDevice?.let { prefs.seenNovelSources().set(mapOf("gone" to LnSourceIdentity(name = it))) }
        restoreEncoded(
            Backup(backupManga = emptyList(), backupNovelSources = listOf(BackupNovelSource(backedUp, "gone"))),
            OPTIONS,
            novelSourceManager = NovelSourceManager(
                scope = testAppScope(),
                installer = { error("loaded the plugins") },
                extensionManager = mockk<ExtensionManager> {
                    every { loadedNovelExtensionsFlow } returns flowOf(emptyList())
                },
                prefs = prefs,
            ),
        )
        return prefs.seenNovelSources().get()["gone"]?.name
    }
}
