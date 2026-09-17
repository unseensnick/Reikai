package mihon.gradle

import org.gradle.api.Project

interface BuildConfig {
    val includeTelemetry: Boolean
    val enableUpdater: Boolean
    val includeDependencyInfo: Boolean

    // RK: link the in-app docs to the nightly docs under /preview/ instead of the stable ones
    val previewDocs: Boolean
}

val Project.Config: BuildConfig get() = object : BuildConfig {
    override val includeTelemetry: Boolean = project.hasProperty("include-telemetry")
    override val enableUpdater: Boolean = project.hasProperty("enable-updater")
    override val includeDependencyInfo: Boolean = project.hasProperty("include-dependency-info")

    // RK
    override val previewDocs: Boolean = project.hasProperty("preview-docs")
}
