package mihon.gradle.extensions

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * Compose UI tests in the module's JVM unit suite. Robolectric hosts the composables and only runs
 * under JUnit 4, which the vintage engine runs beside the suite's JUnit 6 tests. See
 * docs/dev/testing.md.
 */
fun Project.composeUiTests() {
    android {
        testOptions.unitTests.isIncludeAndroidResources = true
    }
    dependencies {
        add("testImplementation", platform(libs.androidx.compose.bom))
        add("testImplementation", libs.androidx.compose.uiTest)
        add("testImplementation", libs.robolectric)
        add("testRuntimeOnly", libs.junit.vintage.engine)
        // The ComponentActivity createComposeRule launches, merged into the debug manifest only.
        add("debugImplementation", libs.androidx.compose.uiTestManifest)
    }
    tasks.withType<Test> {
        // JUnit 6 deprecates the vintage engine and says so once per run; the choice is deliberate.
        systemProperty("junit.vintage.discovery.issue.reporting.enabled", "false")
        // Robolectric's Android 16 sandbox reaches JDK internals for its shared-memory file descriptors.
        jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
    }
}
