pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("mihonx") {
            from(files("gradle/mihon.versions.toml"))
        }
    }
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
        // RK --> Reikai's fork of the WebGPU viewer, released as GitHub release assets with an ivy descriptor
        exclusiveContent {
            forRepository {
                ivy("https://github.com/unseensnick/webgpuviewer/releases/download") {
                    name = "webgpuviewer releases"
                    patternLayout {
                        artifact("v[revision]/[artifact]-[revision].[ext]")
                        ivy("v[revision]/ivy-[revision].xml")
                    }
                    metadataSources { ivyDescriptor() }
                }
            }
            filter { includeModule("ca.mpreg", "webgpuviewer") }
        }
        // RK <--
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Reikai" // RK: build/scan display label (was "Mihon"); app name lives in i18n
include(":app")
include(":baseline-profile")
include(":core-metadata")
include(":core:archive")
include(":core:common")
include(":core:metro")
include(":core:navigation")
include(":data")
include(":domain")
include(":i18n")
include(":icons:custom-material-symbols")
include(":icons:material-symbols")
include(":icons:simple-icons")
include(":presentation-core")
include(":presentation-widget")
include(":source-api")
include(":source-local")
include(":telemetry")
