package mihon.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import reikai.navigation.NovelWebTarget

@Serializable
data object HomeRoute : NavKey

@Serializable
data class DeepLinkRoute(val query: String) : NavKey

@Serializable
data class WebViewRoute(
    val url: String,
    val initialTitle: String? = null,
    val sourceId: Long? = null,
    val novel: NovelWebTarget = NovelWebTarget.None, // RK
) : NavKey

@Serializable
data class MangaNotesRoute(val mangaId: Long) : NavKey

@Serializable
data class MangaRoute(
    val mangaId: Long,
    val fromSource: Boolean = false,
) : NavKey

@Serializable
data object UpcomingRoute : NavKey

@Serializable
data class ExtensionDetailsRoute(val pkgName: String) : NavKey

@Serializable
data object ExtensionFilterRoute : NavKey

@Serializable
data class SourcePreferencesRoute(val sourceId: Long) : NavKey

@Serializable
data object CategoryRoute : NavKey

@Serializable
data object DownloadQueueRoute : NavKey

@Serializable
data class NewUpdateRoute(
    val versionName: String,
    val changelogInfo: String,
    val releaseLink: String,
    val downloadLink: String,
) : NavKey

@Serializable
data object OnboardingRoute : NavKey

@Serializable
data object StatsRoute : NavKey

@Serializable
data object AppLanguageRoute : NavKey

@Serializable
data object BackupSchemaRoute : NavKey {
    const val TITLE = "Backup file schema"
}

@Serializable
data object ClearDatabaseRoute : NavKey

@Serializable
data object CreateBackupRoute : NavKey

@Serializable
data object DebugInfoRoute : NavKey

@Serializable
data object ExtensionStoresRoute : NavKey

@Serializable
data object OpenSourceLicensesRoute : NavKey

@Serializable
data class RestoreBackupRoute(val uri: String) : NavKey

@Serializable
data object SettingsSearchRoute : NavKey

@Serializable
data object WorkerInfoRoute : NavKey {
    const val TITLE = "Worker info"
}
