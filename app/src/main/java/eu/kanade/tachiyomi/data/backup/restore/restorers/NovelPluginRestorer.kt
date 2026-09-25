// Light-novel plugin half of the installed-extension restore. Net-new Reikai file: a backup
// carries the plugin URLs (in the preference backup) but never their scripts, so after a restore the
// app lists plugins nothing can run until each script is fetched again. This brings them back while
// the restore is still running, and names the ones that did not, the way ExtensionRestorer does for
// manga extensions, so "0 errors" cannot hide a library whose novel sources are all missing.
package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.withTimeoutOrNull
import reikai.domain.novel.NovelPreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.install.LnPluginLoadFailure

@Inject
class NovelPluginRestorer(
    private val installer: LnPluginInstaller,
    private val novelPreferences: NovelPreferences,
) {

    /** Reload every restored plugin URL; returns each plugin that did not come back, with why. */
    suspend fun restore(): List<NotRestored> {
        if (novelPreferences.installedPluginUrls().get().isEmpty()) return emptyList()

        // Bounded, because the load fetches each script and revalidates against the added repos: an
        // unreachable repo used to be the reason this ran lazily instead of here. A timeout leaves the
        // plugins to the lazy loader on the next novel screen, and says so.
        val load = withTimeoutOrNull(LOAD_TIMEOUT_MS) { installer.loadInstalled() }
        val failures = installer.failures.value.values.map { NotRestored(it.name, it.reason.label()) }
        return when {
            load == null -> failures + NotRestored(null, "load timed out")
            load.unreachableRepo != null -> failures + NotRestored(null, "repo unreachable: ${load.unreachableRepo}")
            else -> failures + load.dropped.map { NotRestored(it, "no added repo lists it") }
        }
    }

    private fun LnPluginLoadFailure.Reason.label(): String = when (this) {
        LnPluginLoadFailure.Reason.Missing -> "script could not be downloaded"
        LnPluginLoadFailure.Reason.Malformed -> "plugin malformed"
        is LnPluginLoadFailure.Reason.Failed -> message
    }

    /** [name] is null when the load as a whole did not finish, so no one plugin can be blamed. */
    data class NotRestored(val name: String?, val reason: String)

    companion object {
        private const val LOAD_TIMEOUT_MS = 120_000L
    }
}
