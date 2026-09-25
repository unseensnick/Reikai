import mihon.gradle.Config

plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.metro)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.kanade.tachiyomi.core.common"

    // RK --> which docs the app's links open: the nightly docs for a build passed -Ppreview-docs, which
    // nightly.yml does, and the stable docs otherwise. Constants.URL_DOCS builds on it.
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "DOCS_PATH", if (Config.previewDocs) "\"preview/docs\"" else "\"docs\"")
    }
    // RK <--
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    implementation(projects.core.metro)
    implementation(projects.i18n)

    implementation(libs.metro.runtime) // RK: Reikai's own Metro port put it here; upstream lists it last

    api(libs.logcat)

    api(libs.rxJava)

    api(libs.okhttp.core)
    api(libs.okhttp.logging)
    api(libs.okhttp.brotli)
    api(libs.okhttp.dnsOverHttps)
    api(libs.okio)

    implementation(libs.image.decoder)

    implementation(libs.unifile)
    implementation(libs.archive)

    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.jsonOkio)

    api(libs.androidx.preference)
    implementation(libs.androidx.webkit)

    implementation(libs.jsoup)

    // Sort
    implementation(libs.natural.comparator)

    // RK: JavaScript engine is headless QuickJS (dokar3 quickjs-kt), one engine for both the
    // manga extensions-lib helper and Reikai's LN plugin host; a second QuickJS binding would
    // collide on the shared libquickjs.so.
    implementation(libs.quickjs.kt)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
