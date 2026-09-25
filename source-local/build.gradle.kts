plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.metro)
}

android {
    namespace = "tachiyomi.source.local"
}

kotlin {
    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(projects.sourceApi)
    implementation(projects.i18n)

    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.coreMetadata)
    implementation(projects.domain)

    // RK: metro.runtime moved down beside injekt

    implementation(libs.unifile)
    implementation(libs.bundles.serialization)

    implementation(libs.metro.runtime) // RK: beside injekt, where Reikai's own Metro migration put it
    implementation(libs.injekt)
    implementation(libs.jsoup)

    implementation(libs.kotlinx.datetime)
}
