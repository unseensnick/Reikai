package mihon.app.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.interactor.ToggleIncognito
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadJob
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.library.MetadataUpdateJob
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.util.ExtensionInstallActivity
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegateImpl
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.setting.track.BaseOAuthLoginActivity
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import exh.eh.EHentaiUpdateWorker
import exh.favorites.EhFavoritesBackupJob
import exh.md.MangaDexSyncJob
import exh.source.ExhPreferences
import exh.ui.login.EhLoginActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.core.metro.IsDebugBuild
import mihon.domain.extension.interactor.GetExtensionStoreCountAsFlow
import nl.adaptivity.xmlutil.serialization.XML
import reikai.data.novel.update.NovelUpdateJob
import reikai.data.track.TrackerRefreshJob
import reikai.domain.category.GetNovelCategories
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.interactor.ResetNovelCategoryFlags
import reikai.domain.novel.track.NovelDelayedTrackingUpdateJob
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.recommendation.taste.RefreshTrackerLibrary
import reikai.domain.recommendation.taste.TasteLibraryRepository
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownloadJob
import reikai.novel.update.LnPluginUpdateChecker
import reikai.presentation.migrate.flow.MigrationAdapters
import reikai.presentation.migrate.flow.MigrationPickHandoff
import reikai.presentation.widget.UnifiedUpdatesGlanceWidget
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.ResetCategoryFlags
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.ResetViewerFlags
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
interface AppGraph : ViewModelGraph {
    val viewModelFactory: MetroViewModelFactory

    fun inject(app: App)

    fun inject(backupCreateJob: BackupCreateJob)
    fun inject(backupRestoreJob: BackupRestoreJob)
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

    fun inject(secureActivityDelegateImpl: SecureActivityDelegateImpl)

    fun inject(mainActivity: MainActivity)
    fun inject(readerActivity: ReaderActivity)
    fun inject(webViewActivity: WebViewActivity)
    fun inject(baseOAuthLoginActivity: BaseOAuthLoginActivity)
    fun inject(extensionInstallActivity: ExtensionInstallActivity)
    fun inject(ehLoginActivity: EhLoginActivity)
    fun inject(notificationReceiver: NotificationReceiver)

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

    // Read through Context.appGraph by companions, objects and composable bodies, none of which can
    // be member-injected.
    // uiPreferences is also read from attachBaseContext, before any injected field exists.
    // migrationAdapters is read from the migrate screens, which pick one by content type at runtime.
    val uiPreferences: UiPreferences
    val migrationAdapters: MigrationAdapters
    val exhPreferences: ExhPreferences
    val novelPreferences: NovelPreferences
    val reikaiRecommendationPreferences: ReikaiRecommendationPreferences
    val lnPluginUpdateChecker: LnPluginUpdateChecker
    val refreshTrackerLibrary: RefreshTrackerLibrary

    val basePreferences: BasePreferences
    val readerPreferences: ReaderPreferences
    val sourcePreferences: SourcePreferences
    val trackPreferences: TrackPreferences
    val reikaiLibraryPreferences: ReikaiLibraryPreferences
    val reikaiSourcePreferences: ReikaiSourcePreferences

    val trackerManager: TrackerManager
    val chapterCache: ChapterCache
    val coverCache: CoverCache
    val pagePreviewCache: PagePreviewCache
    val downloadCache: DownloadCache
    val mangaMergeManager: MangaMergeManager
    val novelMergeManager: NovelMergeManager
    val migrationPickHandoff: MigrationPickHandoff
    val tasteLibraryRepository: TasteLibraryRepository

    // Interactors are unscoped, so every read builds a fresh instance where Injekt's
    // addSingletonFactory cached one. Safe only because none of them holds state.
    val getCategories: GetCategories
    val getNovelCategories: GetNovelCategories
    val getFavorites: GetFavorites
    val getFlatMetadataById: GetFlatMetadataById
    val getExhFavoriteMangaWithMetadata: GetExhFavoriteMangaWithMetadata
    val getExtensionStoreCountAsFlow: GetExtensionStoreCountAsFlow
    val toggleIncognito: ToggleIncognito
    val trustExtension: TrustExtension
    val resetViewerFlags: ResetViewerFlags
    val resetCategoryFlags: ResetCategoryFlags
    val resetNovelCategoryFlags: ResetNovelCategoryFlags

    // Read by App's cold-start warm-up.
    val sourceManager: SourceManager
    val downloadManager: DownloadManager

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context, @Provides @IsDebugBuild isDebugBuild: Boolean): AppGraph
    }
}
