package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionRestorer.NotRestored
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionRestorer.Reason
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The backups guide tells the user that every extension the restore could not bring back is named in
 * the restore log, so each way an install can end short of Installed must come back with its reason.
 */
class ExtensionRestorerTest {

    private val backup = BackupExtension(pkgName = "ext.foo", name = "Foo")

    private val available = mockk<Extension.Available> {
        every { pkgName } returns "ext.foo"
    }

    private val novelApp = mockk<Extension.Available> {
        every { pkgName } returns "novel.bar"
    }

    private val extensionManager = mockk<ExtensionManager> {
        coEvery { findAvailableExtensions() } just runs
        every { availableExtensionsFlow } returns MutableStateFlow(listOf(available))
        every { getAvailableNovelExtensions() } returns listOf(novelApp)
        every { loadedExtensionsFlow } returns MutableStateFlow(emptyList())
        every { loadedNovelExtensionsFlow } returns MutableStateFlow(emptyList())
    }

    private val restorer = ExtensionRestorer(extensionManager)

    @Test
    fun `an extension a repo offers but that fails to install is reported`() = runTest {
        every { extensionManager.installExtension(available) } returns
            flowOf(InstallStep.Pending, InstallStep.Downloading, InstallStep.Error)

        restorer.restore(listOf(backup)) shouldBe listOf(NotRestored("Foo", Reason.InstallFailed))
    }

    @Test
    fun `an install whose flow throws is reported as failed`() = runTest {
        every { extensionManager.installExtension(available) } returns flow { error("download broke") }

        restorer.restore(listOf(backup)) shouldBe listOf(NotRestored("Foo", Reason.InstallFailed))
    }

    @Test
    fun `an install the user dismissed is reported as cancelled`() = runTest {
        every { extensionManager.installExtension(available) } returns
            flowOf(InstallStep.Installing, InstallStep.Idle)

        restorer.restore(listOf(backup)) shouldBe listOf(NotRestored("Foo", Reason.InstallCancelled))
    }

    @Test
    fun `an install that never finishes is reported as timed out`() = runTest {
        every { extensionManager.installExtension(available) } returns MutableStateFlow(InstallStep.Downloading)

        restorer.restore(listOf(backup)) shouldBe listOf(NotRestored("Foo", Reason.TimedOut))
    }

    @Test
    fun `an extension no repo offers is reported as repo missing`() = runTest {
        val orphan = BackupExtension(pkgName = "ext.orphan", name = "Orphan")

        restorer.restore(listOf(orphan)) shouldBe listOf(NotRestored("Orphan", Reason.RepoMissing))
    }

    // Not stubbed to install: a second install would throw and come back as InstallFailed.
    @Test
    fun `an extension already installed is not installed again`() = runTest {
        every { extensionManager.loadedExtensionsFlow } returns
            MutableStateFlow(listOf(mockk<Extension.Loaded> { every { pkgName } returns "ext.foo" }))

        restorer.restore(listOf(backup)).shouldBeEmpty()
    }

    @Test
    fun `a novel extension app already installed is not installed again`() = runTest {
        every { extensionManager.loadedNovelExtensionsFlow } returns
            MutableStateFlow(listOf(mockk<Extension.Loaded> { every { pkgName } returns "novel.bar" }))

        restorer.restore(listOf(BackupExtension(pkgName = "novel.bar", name = "Bar"))).shouldBeEmpty()
    }

    @Test
    fun `a novel extension app a repo offers is reinstalled`() = runTest {
        every { extensionManager.installExtension(novelApp) } returns flowOf(InstallStep.Installed)

        restorer.restore(listOf(BackupExtension(pkgName = "novel.bar", name = "Bar"))).shouldBeEmpty()
    }

    @Test
    fun `an extension that installs is not reported`() = runTest {
        every { extensionManager.installExtension(available) } returns
            flowOf(InstallStep.Downloading, InstallStep.Installed)

        restorer.restore(listOf(backup)).shouldBeEmpty()
    }
}
