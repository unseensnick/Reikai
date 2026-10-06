package reikai.novel.install

import kotlinx.coroutines.withTimeoutOrNull
import reikai.util.crashLogNotLoadedEntry
import reikai.util.runCatchingCancellable
import kotlin.time.Duration.Companion.seconds

/**
 * The crash log's lines for installed plugins that did not load, each with the stack trace a manga
 * extension's carries. The crash screen runs in its own process, where nothing has loaded the plugins,
 * so this runs the first load, bounded, as ExtensionManager scans the apps when it is built. That load
 * writes the installer's preferences, which is safe there only because the main process is gone.
 */
suspend fun novelPluginCrashLogEntries(installer: LnPluginInstaller): List<String> {
    withTimeoutOrNull(5.seconds) { runCatchingCancellable { installer.awaitFirstLoad() } }
    return installer.failures.value.values
        .sortedBy { it.name }
        .map { plugin ->
            val (reason, stackTrace) = when (val reason = plugin.reason) {
                is LnPluginLoadFailure.Reason.Missing -> "Script missing (${reason.message})" to null
                LnPluginLoadFailure.Reason.Malformed -> "Malformed" to null
                is LnPluginLoadFailure.Reason.Failed -> "Failed (${reason.message})" to reason.stackTrace
            }
            crashLogNotLoadedEntry("${plugin.name} (novel plugin)", plugin.version ?: "?", reason, stackTrace)
        }
}
