package reikai.util

/**
 * One crash-log line for an extension or novel plugin that did not load, in Mihon's CrashLogUtil
 * format: name, installed version, reason, then a failure's stack trace indented under it.
 */
fun crashLogNotLoadedEntry(name: String, installed: String, reason: String, stackTrace: String?): String =
    buildString {
        appendLine("- $name")
        appendLine("  Installed: $installed")
        append("  Not loaded: $reason")
        if (stackTrace != null) {
            appendLine()
            append(stackTrace.trimEnd().prependIndent("  "))
        }
    }
