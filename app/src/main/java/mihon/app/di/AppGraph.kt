package mihon.app.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadJob
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.library.MetadataUpdateJob
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import exh.eh.EHentaiUpdateWorker
import exh.favorites.EhFavoritesBackupJob
import exh.md.MangaDexSyncJob
import exh.source.ExhPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.core.metro.IsDebugBuild
import nl.adaptivity.xmlutil.serialization.XML
import reikai.data.novel.update.NovelUpdateJob
import reikai.data.track.TrackerRefreshJob
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.track.NovelDelayedTrackingUpdateJob
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.recommendation.taste.RefreshTrackerLibrary
import reikai.novel.download.NovelDownloadJob
import reikai.novel.update.LnPluginUpdateChecker
import reikai.presentation.widget.UnifiedUpdatesGlanceWidget
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
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

    fun inject(libraryUpdateJob: LibraryUpdateJob)
    fun inject(metadataUpdateJob: MetadataUpdateJob)
    fun inject(downloadJob: DownloadJob)
    fun inject(novelDownloadJob: NovelDownloadJob)
    fun inject(novelUpdateJob: NovelUpdateJob)
    fun inject(trackerRefreshJob: TrackerRefreshJob)
    fun inject(eHentaiUpdateWorker: EHentaiUpdateWorker)
    fun inject(ehFavoritesBackupJob: EhFavoritesBackupJob)
    fun inject(mangaDexSyncJob: MangaDexSyncJob)
    fun inject(delayedTrackingUpdateJob: DelayedTrackingUpdateJob)
    fun inject(novelDelayedTrackingUpdateJob: NovelDelayedTrackingUpdateJob)

    // Mihon's own widgets inject through PresentationWidgetGraph, contributed from presentation-widget.
    fun inject(unifiedUpdatesGlanceWidget: UnifiedUpdatesGlanceWidget)

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

    // Read through Context.appGraph by companions and objects, which cannot be member-injected.
    val exhPreferences: ExhPreferences
    val novelPreferences: NovelPreferences
    val reikaiRecommendationPreferences: ReikaiRecommendationPreferences
    val lnPluginUpdateChecker: LnPluginUpdateChecker
    val refreshTrackerLibrary: RefreshTrackerLibrary

    // Read by App's cold-start warm-up.
    val sourceManager: SourceManager
    val downloadManager: DownloadManager

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context, @Provides @IsDebugBuild isDebugBuild: Boolean): AppGraph
    }
}
