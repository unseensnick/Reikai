package mihon.app.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.core.metro.IsDebugBuild
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.upcoming.service.UpcomingPreferences
import tachiyomi.domain.updates.service.UpdatesPreferences
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem

@DependencyGraph(
    scope = AppScope::class,
    bindingContainers = [AppBindings::class, ReikaiBindings::class],
)
interface AppGraph {
    fun inject(app: App)

    val context: Context

    val json: Json
    val xml: XML
    val protoBuf: ProtoBuf
    val database: Database

    val preferenceStore: PreferenceStore
    val networkHelper: NetworkHelper
    val javaScriptEngine: JavaScriptEngine
    val storageManager: StorageManager
    val localSourceFileSystem: LocalSourceFileSystem
    val localCoverManager: LocalCoverManager

    val networkPreferences: NetworkPreferences
    val securityPreferences: SecurityPreferences
    val privacyPreferences: PrivacyPreferences
    val libraryPreferences: LibraryPreferences
    val upcomingPreferences: UpcomingPreferences
    val updatesPreferences: UpdatesPreferences
    val downloadPreferences: DownloadPreferences
    val backupPreferences: BackupPreferences
    val storagePreferences: StoragePreferences

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context, @Provides @IsDebugBuild isDebugBuild: Boolean): AppGraph
    }
}
