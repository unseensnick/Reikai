// Installed-extensions backup. Net-new Reikai file: reinstalls the manga and novel
// extensions a backup recorded. Must run after the extension repos are restored, since the available
// list is fetched from them. Installs go through the standard installer (respecting the user's
// installer mode). Every extension that does not come back is returned with its reason for the
// restore log, which the backups guide promises names each one.
package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

@Inject
class ExtensionRestorer(
    private val extensionManager: ExtensionManager,
) {

    /** Reinstall the backed-up extensions; returns each one that did not come back, with why. */
    suspend fun restore(backupExtensions: List<BackupExtension>): List<NotRestored> = coroutineScope {
        if (backupExtensions.isEmpty()) return@coroutineScope emptyList()

        extensionManager.findAvailableExtensions()
        // availableExtensionsFlow is a stateIn(Lazily) flow, so its .value lags behind the fetch we just
        // triggered; reading it synchronously here returned an empty list and reported every extension
        // as "repo missing". Await the populated emission instead, bounded so a genuinely empty result
        // (no repos / fetch failed) still falls through to the unmatched log.
        val available: List<Extension.Available> = withTimeoutOrNull(AVAILABLE_WAIT_MS) {
            extensionManager.availableExtensionsFlow.first { it.isNotEmpty() }
        }.orEmpty() +
            // Novel apps too, which the manager keeps apart. Read off the map rather than a second lazy
            // flow, which could still be empty when the first answers.
            extensionManager.getAvailableNovelExtensions()
        val availableByPkg = available.associateBy { it.pkgName }
        val installedPkgs = (
            extensionManager.loadedExtensionsFlow.first() +
                extensionManager.loadedNovelExtensionsFlow.first()
            )
            .mapTo(HashSet()) { it.pkgName }

        backupExtensions
            .filterNot { it.pkgName in installedPkgs }
            .map { backupExtension ->
                // Install on the restore's own scope and await it, instead of firing it onto the
                // app-lifetime extensionManager.scope. The fire-and-forget installs kept landing after the
                // restore "finished", racing the trust evaluation and colliding with the user's own actions.
                async {
                    val match = availableByPkg[backupExtension.pkgName]
                    val reason = if (match == null) Reason.RepoMissing else install(match)
                    reason?.let { NotRestored(backupExtension.name, it) }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    /** Null when the extension installed; bounded so one slow download can't stall the whole restore. */
    private suspend fun install(extension: Extension.Available): Reason? {
        val step = try {
            withTimeoutOrNull(INSTALL_TIMEOUT_MS) {
                extensionManager.installExtension(extension).first { it.isCompleted() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Extension restore failed: ${extension.pkgName}" }
            return Reason.InstallFailed
        }
        return when (step) {
            null -> Reason.TimedOut
            InstallStep.Installed -> null
            // The installer reports a dismissed prompt or a cancelled queue entry as Idle.
            InstallStep.Idle -> Reason.InstallCancelled
            else -> Reason.InstallFailed
        }
    }

    data class NotRestored(val name: String, val reason: Reason)

    enum class Reason(val label: String) {
        RepoMissing("repo missing"),
        InstallFailed("install failed"),
        InstallCancelled("install cancelled"),
        TimedOut("install timed out"),
    }

    companion object {
        private const val AVAILABLE_WAIT_MS = 20_000L
        private const val INSTALL_TIMEOUT_MS = 90_000L
    }
}
