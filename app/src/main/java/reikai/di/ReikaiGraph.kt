package reikai.di

import eu.kanade.domain.source.interactor.ToggleIncognito
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.data.coil.MangaCoverMetadata
import exh.GalleryAdder
import exh.eh.EHentaiUpdateHelper
import exh.eh.EHentaiUpdateWorker
import exh.favorites.EhFavoritesBackupJob
import exh.md.MangaDexSyncJob
import exh.pref.DelegateSourcePreferences
import exh.source.ExhPreferences
import exh.uconfig.EHConfigurator
import exh.ui.login.EhLoginActivity
import reikai.data.novel.update.NovelUpdateJob
import reikai.data.track.TrackerRefreshJob
import reikai.domain.category.GetNovelCategories
import reikai.domain.extension.ExtensionUpdateCounts
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.interactor.RepairNovelDetails
import reikai.domain.novel.track.NovelDelayedTrackingUpdateJob
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.recommendation.taste.RefreshTrackerLibrary
import reikai.domain.recommendation.taste.TasteLibraryRepository
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadJob
import reikai.novel.font.NovelFontManager
import reikai.novel.network.NovelImageRequests
import reikai.novel.source.NovelSourceManager
import reikai.novel.source.ireader.IReaderHostServices
import reikai.novel.update.LnPluginUpdateChecker
import reikai.novel.update.LnPluginUpdateNotifier
import reikai.presentation.details.MangaEntryCoverViewModel
import reikai.presentation.library.MangaLibraryAdapter
import reikai.presentation.library.NovelLibraryAdapter
import reikai.presentation.migrate.flow.MigrationAdapters
import reikai.presentation.migrate.flow.MigrationPickHandoff
import reikai.presentation.novel.details.NovelCoverViewModel
import reikai.presentation.recents.MangaRecentsAdapter
import reikai.presentation.recents.NovelRecentsAdapter
import reikai.presentation.widget.UnifiedUpdatesGlanceWidget
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.InsertFlatMetadata

/**
 * Reikai's members of the app graph. `AppGraph` extends this, so each is still read as
 * `context.appGraph.x`; keeping them here leaves `AppGraph` close enough to Mihon's to sync by hunk.
 */
interface ReikaiGraph {
    fun inject(novelDownloadJob: NovelDownloadJob)
    fun inject(novelUpdateJob: NovelUpdateJob)
    fun inject(trackerRefreshJob: TrackerRefreshJob)
    fun inject(eHentaiUpdateWorker: EHentaiUpdateWorker)
    fun inject(ehFavoritesBackupJob: EhFavoritesBackupJob)
    fun inject(mangaDexSyncJob: MangaDexSyncJob)
    fun inject(novelDelayedTrackingUpdateJob: NovelDelayedTrackingUpdateJob)

    // Mihon's own widgets inject through PresentationWidgetGraph, contributed from presentation-widget.
    fun inject(unifiedUpdatesGlanceWidget: UnifiedUpdatesGlanceWidget)

    fun inject(ehLoginActivity: EhLoginActivity)

    // App's cold-start warm-up reads it beside Mihon's networkHelper, sourceManager and downloadManager.
    val database: Database
    val preferenceStore: PreferenceStore

    // Read through Context.appGraph by companions, objects and composable bodies, none of which can
    // be member-injected. migrationAdapters is read from the migrate screens, which pick one by
    // content type at runtime.
    val migrationAdapters: MigrationAdapters
    val exhPreferences: ExhPreferences
    val delegateSourcePreferences: DelegateSourcePreferences
    val ehConfigurator: EHConfigurator
    val eHentaiUpdateHelper: EHentaiUpdateHelper

    // Unscoped on purpose: the adder snapshots the enabled-language and disabled-source preferences
    // at construction, so every read has to build a fresh one.
    val galleryAdder: GalleryAdder
    val novelPreferences: NovelPreferences
    val novelSourceManager: NovelSourceManager
    val iReaderHostServices: IReaderHostServices // the extension loader builds IReader sources with it
    val novelImageRequests: NovelImageRequests // novel covers and pictures take their source's headers
    val extensionUpdateCounts: ExtensionUpdateCounts // the Browse badge counts plugin updates too
    val reikaiRecommendationPreferences: ReikaiRecommendationPreferences
    val lnPluginUpdateChecker: LnPluginUpdateChecker
    val lnPluginUpdateNotifier: LnPluginUpdateNotifier // the plugin update job posts and clears its notice
    val refreshTrackerLibrary: RefreshTrackerLibrary
    val reikaiLibraryPreferences: ReikaiLibraryPreferences
    val reikaiSourcePreferences: ReikaiSourcePreferences

    val pagePreviewCache: PagePreviewCache
    val mangaCoverMetadata: MangaCoverMetadata
    val novelDownloadCache: NovelDownloadCache // Settings invalidates both download indexes
    val mangaMergeManager: MangaMergeManager
    val novelMergeManager: NovelMergeManager
    val novelChapterRepository: NovelChapterRepository // NovelUpdates finds the release a read links to
    val novelFontManager: NovelFontManager
    val migrationPickHandoff: MigrationPickHandoff

    // The two details adapters build their cover model for whichever entry the source chip is showing,
    // so the id arrives at call time and the factory is what the graph can hand over.
    val mangaCoverViewModelFactory: MangaEntryCoverViewModel.Factory
    val novelCoverViewModelFactory: NovelCoverViewModel.Factory

    // The library engine owns exactly one adapter pair, built from the tab's own three models, so the
    // models arrive at call time here too.
    val mangaLibraryAdapterFactory: MangaLibraryAdapter.Factory
    val novelLibraryAdapterFactory: NovelLibraryAdapter.Factory
    val mangaRecentsAdapterFactory: MangaRecentsAdapter.Factory
    val novelRecentsAdapterFactory: NovelRecentsAdapter.Factory
    val tasteLibraryRepository: TasteLibraryRepository

    // Interactors are unscoped, so every read builds a fresh instance. That matches the pre-port
    // shape: Injekt registered every one of these with addFactory, never addSingletonFactory.
    val getNovelCategories: GetNovelCategories

    // The metadata trio backs source-api's MetadataSource contract, which installed extensions
    // implement, so these three are reached through Injekt rather than the graph.
    val getManga: GetManga
    val getFlatMetadataById: GetFlatMetadataById
    val insertFlatMetadata: InsertFlatMetadata
    val getExhFavoriteMangaWithMetadata: GetExhFavoriteMangaWithMetadata
    val toggleIncognito: ToggleIncognito
    val repairNovelDetails: RepairNovelDetails
}
