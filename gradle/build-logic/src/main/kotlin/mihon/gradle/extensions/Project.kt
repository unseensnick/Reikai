package mihon.gradle.extensions

import com.android.build.api.dsl.CommonExtension
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.accessors.dm.LibrariesForMihonx
import org.gradle.api.Project
import org.gradle.api.plugins.PluginManager
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.the
import org.gradle.kotlin.dsl.withType

internal val Project.libs get() = the<LibrariesForLibs>()
internal val Project.mihonx get() = the<LibrariesForMihonx>()

internal fun Project.plugins(block: PluginManager.() -> Unit) {
    pluginManager.apply(block)
}

internal fun Project.android(block: CommonExtension.() -> Unit) {
    extensions.configure(block)
}

fun Project.configureTest() {
    tasks.withType<Test> {
        useJUnitPlatform()
        // RK: one test process per core, up to 4, so per-class startup no longer adds up in one
        maxParallelForks = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        testLogging {
            events(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
        }
    }
}
