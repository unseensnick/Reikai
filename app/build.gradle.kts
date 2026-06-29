import mihon.gradle.Config
import mihon.gradle.getBuildTime
import mihon.gradle.getLatestCommitCount
import mihon.gradle.getLatestCommitSha
import mihon.gradle.tasks.ReplaceShortcutsPlaceholderTask
import java.io.FileInputStream
import java.util.Properties
import kotlin.io.encoding.Base64

plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.compose)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.androidx.baselineProfile)
    alias(libs.plugins.kotlin.serialization)
}

if (Config.includeTelemetry) {
    pluginManager.apply {
        apply(libs.plugins.google.services.get().pluginId)
        apply(libs.plugins.firebase.crashlytics.get().pluginId)
    }
}

// RK --> AGP-native release signing (adapted from Mihon "Sign APK with AGP", upstream 6552ffe31).
// The release build is signed with the real key when it's available (CI secrets under unseensnick/*,
// or a local keystore.properties); see the signingConfigs block in android {}.
val keystorePropertiesFile = rootProject.file("keystore.properties")
// RK <--

android {
    namespace = "eu.kanade.tachiyomi"

    defaultConfig {
        // RK --> Reikai identity: keep eu.kanade.tachiyomi base + .y2k suffix so existing installs upgrade in place.
        // versionName: Reikai's own SemVer for the Mihon era, starting at 0.1.0 (drops the old 5-segment Yokai scheme).
        // versionCode must keep climbing and stay above the last Yokai-based build (168) so installs upgrade in place.
        applicationId = "eu.kanade.tachiyomi"

        versionCode = 175
        versionName = "0.1.5"
        // RK <--

        buildConfigField("String", "COMMIT_COUNT", "\"${getLatestCommitCount()}\"")
        buildConfigField("String", "COMMIT_SHA", "\"${getLatestCommitSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLatestCommitTime = false)}\"")
        buildConfigField("boolean", "TELEMETRY_INCLUDED", "${Config.includeTelemetry}")
        buildConfigField("boolean", "UPDATER_ENABLED", "${Config.enableUpdater}")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // RK --> sign release with the real key when available, reconfiguring the "debug" signing config
    // that the release buildType references. Gated on REIKAI_GITHUB_RELEASE (set only by release.yml /
    // preview.yml, which also pass the keystore secrets), so the unsigned PR build (build_check.yml)
    // never reads a keystore that isn't there. Adapted from Mihon's MIHON_GITHUB_RELEASE gate (upstream
    // 50d0e8ae0). Locally a keystore.properties signs; with neither, "debug" stays the default debug key.
    if (System.getenv("REIKAI_GITHUB_RELEASE").toBoolean()) {
        val tempStoreFile = file(System.getenv("RUNNER_TEMP")).resolve("reikai.keystore")
        val storeFileBytes = System.getenv("storeFileBase64").let(Base64::decode)
        tempStoreFile.outputStream().use { it.write(storeFileBytes) }
        signingConfigs {
            named("debug") {
                storeFile = tempStoreFile
                storePassword = System.getenv("storePassword")
                keyAlias = System.getenv("keyAlias")
                keyPassword = System.getenv("keyPassword")
            }
        }
    } else if (keystorePropertiesFile.exists()) {
        val keystoreProperties = FileInputStream(keystorePropertiesFile).use { Properties().apply { load(it) } }
        signingConfigs {
            named("debug") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    // RK <--

    buildTypes {
        val debug = getByName("debug") {
            applicationIdSuffix = ".debugY2k" // RK: match existing Reikai debug package
            versionNameSuffix = "-${getLatestCommitCount()}"
            isPseudoLocalesEnabled = true
        }
        val release = getByName("release") {
            isMinifyEnabled = Config.enableCodeShrink
            isShrinkResources = Config.enableCodeShrink

            // RK --> existing Reikai release package; signed with the real key when CI secrets or a
            // local keystore.properties are present (see the signingConfigs block above), else debug-signed.
            applicationIdSuffix = ".y2k"
            signingConfig = signingConfigs.getByName("debug")
            // RK <--

            isProfileable = true

            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLatestCommitTime = true)}\"")
        }

        val commonMatchingFallbacks = listOf(release.name)

        create("foss") {
            initWith(release)

            applicationIdSuffix = ".foss"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("preview") {
            initWith(release)

            applicationIdSuffix = ".debug"

            versionNameSuffix = debug.versionNameSuffix
            signingConfig = debug.signingConfig

            matchingFallbacks.addAll(commonMatchingFallbacks)

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLatestCommitTime = false)}\"")
        }
        create("benchmark") {
            initWith(release)

            versionNameSuffix = "-benchmark"
            applicationIdSuffix = ".benchmark"

            signingConfig = debug.signingConfig

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
    }

    sourceSets {
        // RK: preview has its own launcher icon in src/preview/res (the nightly variant); benchmark
        // (unpublished) still borrows the debug icon. Adding src/debug/res to preview too would
        // duplicate every ic_launcher* resource and fail mergePreviewResources.
        getByName("benchmark").res.directories.add("src/debug/res")
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols += listOf(
                "libandroidx.graphics.path",
                "libarchive-jni",
                "libconscrypt_jni",
                "libimagedecoder",
                "libquickjs",
                "libsqlite3x",
            )
                .map { "**/$it.so" }
        }
        resources {
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "LICENSE.txt",
                "META-INF/**/*.properties",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/README.md",
            )
        }
    }

    dependenciesInfo {
        includeInApk = Config.includeDependencyInfo
        includeInBundle = Config.includeDependencyInfo
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

baselineProfile {
    baselineProfileOutputDir = "baselineProfiles"
    mergeIntoMain = true
}

dependencies {
    baselineProfile(projects.baselineProfile)

    implementation(projects.i18n)
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.sourceLocal)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.presentationWidget)
    implementation(projects.telemetry)

    // RK: Glance for the unified manga + novel updates home-screen widget (hosted in the app module
    // because it needs the novel query/cover types that live here; presentation-widget can't see them)
    implementation(libs.androidx.glance.appWidget)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.materialIcons)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.animationGraphics)
    debugImplementation(libs.androidx.compose.uiTooling)
    implementation(libs.androidx.compose.uiToolingPreview)
    implementation(libs.androidx.compose.uiUtil)

    implementation(libs.androidx.interpolator)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.palette)

    implementation(libs.androidx.sqlite.bundled)

    implementation(libs.kotlin.reflect)

    implementation(libs.bundles.kotlinx.coroutines)

    implementation(libs.sqldelight.async)

    // AndroidX libraries
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appCompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.constraintLayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.coreSplashScreen)
    // RK: MediaSession + media-style notification for novel read-aloud (TTS) background playback
    implementation(libs.androidx.media)
    implementation(libs.androidx.recyclerView)
    implementation(libs.androidx.viewPager)
    implementation(libs.androidx.profileInstaller)

    implementation(libs.bundles.androidx.lifecycle)

    // Job scheduling
    implementation(libs.androidx.work)

    // RxJava
    implementation(libs.rxJava)

    // Networking
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.conscrypt) // TLS 1.3 support for Android < 10

    // Data serialization (JSON, protobuf, xml)
    implementation(libs.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // Disk
    implementation(libs.diskLruCache)
    implementation(libs.unifile)

    // Preferences
    implementation(libs.androidx.preference)

    // Dependency injection
    implementation(libs.injekt)

    // RK: headless QuickJS (dokar3) for the LN plugin host (reikai.novel.host)
    implementation(libs.quickjs.kt)

    // Image loading
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingScaleImageView) {
        exclude(module = "image-decoder")
    }
    implementation(libs.image.decoder)

    // UI libraries
    implementation(libs.material)
    implementation(libs.flexibleAdapter)
    implementation(libs.photoView)
    implementation(libs.directionalViewPager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.composeRichEditor)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.bundles.voyager)
    implementation(libs.composeMaterialMotion)
    implementation(libs.swipe)
    implementation(libs.composeWebview)
    implementation(libs.composeGrid)
    implementation(libs.reorderable)
    implementation(libs.bundles.markdown)
    implementation(libs.materialKolor)

    // Logging
    implementation(libs.logcat)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // String similarity
    implementation(libs.stringSimilarity)

    // Tests
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // RK: instrumented test for the headless LN plugin host (network-dependent, on-device only).
    // quickjs-kt is a main dep above, so androidTest sees it transitively.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakCanary.android)
    implementation(libs.leakCanary.plumber)

    testImplementation(libs.kotlinx.coroutines.test)
}

androidComponents {
    onVariants { variant ->
        val resSource = variant.sources.res ?: return@onVariants

        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val replaceShortcutsPlaceholderTask = tasks.register<ReplaceShortcutsPlaceholderTask>(
            "replace${variantName}ShortcutPlaceholder",
        ) {
            applicationId.set(variant.applicationId)
            shortcutsFile.set(projectDir.resolve("src/main/shortcuts.xml"))
        }
        resSource.addGeneratedSourceDirectory(replaceShortcutsPlaceholderTask) { it.outputDir }
    }

    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}
