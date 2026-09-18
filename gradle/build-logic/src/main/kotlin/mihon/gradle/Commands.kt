package mihon.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

// Git is needed in your system PATH for these commands to work.
// If it's not installed, you can return a random value as a workaround
fun Project.getLatestCommitCount(): String {
    return exec("git rev-list --count HEAD")
    // return "1"
}

fun Project.getLatestCommitSha(): String {
    return exec("git rev-parse --short HEAD")
    // return "1"
}

// RK --> the current-time branch became getCurrentBuildTime: read here, at configuration, the
// configuration cache cannot see the clock, so a cache hit replayed the first build's time.

/**
 * @return An ISO 8601 formatted string of the last Git commit's time, in UTC.
 */
fun Project.getBuildTime(): String {
    val epoch = exec("git log -1 --format=%ct").toLong()
    return Instant.fromEpochSeconds(epoch).toString()
}

/**
 * The current time in UTC as ISO 8601, whole seconds. Only a provider: resolved when a task runs,
 * a value source is not a configuration input, so each build reads its own time without
 * invalidating the configuration cache.
 */
fun Project.getCurrentBuildTime(): Provider<String> = providers.of(BuildTimeValueSource::class.java) {}

abstract class BuildTimeValueSource : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String {
        val now = Clock.System.now()
        return (now - now.nanosecondsOfSecond.nanoseconds).toString()
    }
}
// RK <--

fun Project.exec(command: String): String {
    return providers.exec {
        commandLine = command.split(" ")
    }
        .standardOutput
        .asText
        .get()
        .trim()
}
