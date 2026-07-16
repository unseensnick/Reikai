import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(mihonx.plugins.kotlin.multiplatform)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    @Suppress("UnstableApiUsage")
    android {
        namespace = "eu.kanade.tachiyomi.source"
        optimization {
            consumerKeepRules.file("consumer-proguard.pro")
        }

        // TODO(antsy): Remove when https://youtrack.jetbrains.com/issue/KT-83319 is resolved
        withHostTest { }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    dependencies {
        api(libs.kotlinx.serialization.json)
        api(libs.injekt)
        api(libs.rxJava)
        api(libs.jsoup)
        // RK: EXH gallery metadata classes label their fields via MR string resources.
        api(projects.i18n)

        implementation(platform(libs.androidx.compose.bom))
        implementation(libs.androidx.compose.runtime)
    }

    sourceSets {
        androidMain {
            dependencies {
                implementation(projects.core.common)
                api(libs.androidx.preference)
            }
        }
        // No generated accessor for this one, unlike androidMain.
        getByName("androidHostTest") {
            dependencies {
                // Listed individually: this source set's DSL takes single dependencies, not the
                // `test` bundle the non-multiplatform modules use.
                implementation(libs.junit.jupiter)
                implementation(libs.kotest.assertions)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}
