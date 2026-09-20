package mihon.gradle

import org.gradle.api.Project

interface BuildConfig {
    val includeTelemetry: Boolean
    val uploadCrashlyticsMapping: Boolean
    val enableUpdater: Boolean
    val includeDependencyInfo: Boolean

    // RK: link the in-app docs to the nightly docs under /preview/ instead of the stable ones
    val previewDocs: Boolean
}

private enum class Distribution {
    LOCAL,
    CI,
    GITHUB,
    FOSS,
}

val Project.Config: BuildConfig get() = object : BuildConfig {
    private val distribution: Distribution = project.providers.gradleProperty("dist").orNull
        ?.let { name ->
            Distribution.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: error("Unknown -Pdist=$name, expected one of $distributionNames")
        }
        ?: Distribution.LOCAL

    override val includeTelemetry: Boolean = project.flag("include-telemetry")
        ?: (distribution == Distribution.CI || distribution == Distribution.GITHUB)

    override val uploadCrashlyticsMapping: Boolean = includeTelemetry && (distribution == Distribution.GITHUB)

    override val enableUpdater: Boolean = project.flag("enable-updater")
        ?: (distribution != Distribution.LOCAL)

    override val includeDependencyInfo: Boolean = project.flag("include-dependency-info") ?: false

    // RK: no distribution default, because our nightly and stable are both GITHUB and differ only here
    override val previewDocs: Boolean = project.flag("preview-docs") ?: false
}

private val distributionNames = Distribution.entries.joinToString { it.name.lowercase() }

private fun Project.flag(name: String): Boolean? = providers.gradleProperty(name).orNull
    ?.let { it.isEmpty() || it.toBoolean() }
