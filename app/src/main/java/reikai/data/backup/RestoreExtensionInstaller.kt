package reikai.data.backup

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.PreferenceValue
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.repository.ExtensionStoreRepository
import reikai.novel.install.LnPluginInstaller
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.registry.LnRepoRegistries
import reikai.novel.registry.toMetadata
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.system.logcat

/**
 * After a restore, adds the backup repos the user trusted and installs the backup's apps and plugins this
 * device lacks from the repos now on it, one at a time through the normal installers. A repo is added as
 * the Add repo button adds one, read live, and only while it still signs with the key the user saw.
 */
@Inject
class RestoreExtensionInstaller(
    private val sources: RestoreInstallSources,
    private val storeService: ExtensionStoreService,
    private val storeRepository: ExtensionStoreRepository,
    private val pluginRepos: LnRepoRegistries,
    private val extensionManager: ExtensionManager,
    private val pluginInstaller: LnPluginInstaller,
) {

    /** Returns a line for the restore log per trusted repo that could not be added. */
    suspend fun addTrusted(approved: ApprovedRepos): List<String> {
        val failed = mutableListOf<String>()
        approved.apps.forEach { (url, key) ->
            val live = storeService.fetch(url).getOrNull()
            when {
                live == null -> failed += "Repo not added (unreachable): $url"
                live.signingKey != key -> failed += "Repo not added (signing key changed): $url"
                else -> storeRepository.upsert(live)
            }
        }
        approved.plugins.forEach { url ->
            pluginRepos.add(url).onFailure { failed += "Repo not added (unreachable): $url" }
        }
        return failed
    }

    /** Returns each of the backup's missing apps and plugins that did not come back, with why. */
    suspend fun install(apps: List<BackupExtension>, preferences: Map<String, PreferenceValue>): List<NotInstalled> {
        val missing = sources.missing(apps, preferences)
        if (missing.isEmpty()) return emptyList()
        val listings = sources.device()
        return missing.mapNotNull { item ->
            val reason = when (item) {
                is RestoreItem.App -> listings.find(item)?.let { install(it) }
                is RestoreItem.Plugin -> listings.find(item)?.let { install(it) }
            } ?: Reason.NotListed
            NotInstalled(item.name, reason).takeUnless { reason == Reason.Installed }
        }
    }

    private suspend fun install(extension: Extension.Available): Reason {
        val step = runCatchingCancellable {
            // Bounded so one slow download or an unanswered prompt can't stall the whole restore.
            withTimeoutOrNull(INSTALL_TIMEOUT_MS) {
                extensionManager.installExtension(extension).first { it.isCompleted() }
            }
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "Extension restore failed: ${extension.pkgName}" }
            return Reason.InstallFailed
        }
        return when (step) {
            null -> Reason.TimedOut
            InstallStep.Installed -> Reason.Installed
            // The installer reports a dismissed prompt or a cancelled queue entry as Idle.
            InstallStep.Idle -> Reason.InstallCancelled
            else -> Reason.InstallFailed
        }
    }

    private suspend fun install(entry: LnRegistryEntry): Reason =
        runCatchingCancellable { pluginInstaller.installFromUrl(entry.url, entry.toMetadata()) }.fold(
            onSuccess = { Reason.Installed },
            onFailure = {
                logcat(LogPriority.WARN, it) { "Plugin restore failed: ${entry.url}" }
                Reason.InstallFailed
            },
        )

    data class NotInstalled(val name: String, val reason: Reason)

    enum class Reason(val label: String) {
        Installed("installed"),
        NotListed("in no trusted repo"),
        InstallFailed("install failed"),
        InstallCancelled("install cancelled"),
        TimedOut("install timed out"),
    }

    private companion object {
        const val INSTALL_TIMEOUT_MS = 90_000L
    }
}
