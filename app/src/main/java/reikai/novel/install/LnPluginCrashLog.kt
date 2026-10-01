package reikai.novel.install

import kotlinx.coroutines.withTimeoutOrNull
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
            buildString {
                appendLine("- ${plugin.name} (novel plugin)")
                appendLine("  Installed: ${plugin.version ?: "?"}")
                when (val reason = plugin.reason) {
                    is LnPluginLoadFailure.Reason.Missing ->
                        append("  Not loaded: Script missing (${reason.message})")
                    LnPluginLoadFailure.Reason.Malformed -> append("  Not loaded: Malformed")
                    is LnPluginLoadFailure.Reason.Failed -> {
                        appendLine("  Not loaded: Failed (${reason.message})")
                        append(reason.stackTrace.trimEnd().prependIndent("  "))
                    }
                }
            }
        }
}
