package mihon.app.di.injekt

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.repository.ExtensionStoreRepository
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository
import tachiyomi.domain.manga.repository.MangaMetadataRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.release.service.ReleaseService
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.upcoming.service.UpcomingPreferences
import tachiyomi.domain.updates.repository.UpdatesRepository
import tachiyomi.domain.updates.service.UpdatesPreferences
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory

/**
 * Hands Metro-owned singletons back to Injekt, which stays as the runtime facade installed
 * extensions resolve against.
 *
 * A type listed here must have its registration deleted from the Injekt modules in the same
 * change. Registered in both places, the app runs with two instances and loses state silently
 * instead of crashing. SqlDriver and AndroidStorageFolderProvider are deliberately absent: their
 * only consumers moved into the graph with them.
 *
 * Every entry is a Provider registered as a factory, never an eager instance, because
 * LegacyYokaiDbImporter has to move an incompatible database aside before anything opens it, and
 * it runs after this module is imported. See docs/dev/plans/legacy-yokai-import.md.
 */
@Inject
class MetroInteropModule(
    private val json: Provider<Json>,
    private val xml: Provider<XML>,
    private val protoBuf: Provider<ProtoBuf>,
    private val database: Provider<Database>,

    private val preferenceStore: Provider<PreferenceStore>,
    private val networkHelper: Provider<NetworkHelper>,
    private val javaScriptEngine: Provider<JavaScriptEngine>,
    private val storageManager: Provider<StorageManager>,
    private val localSourceFileSystem: Provider<LocalSourceFileSystem>,
    private val localCoverManager: Provider<LocalCoverManager>,

    private val networkPreferences: Provider<NetworkPreferences>,
    private val securityPreferences: Provider<SecurityPreferences>,
    private val privacyPreferences: Provider<PrivacyPreferences>,
    private val libraryPreferences: Provider<LibraryPreferences>,
    private val upcomingPreferences: Provider<UpcomingPreferences>,
    private val updatesPreferences: Provider<UpdatesPreferences>,
    private val downloadPreferences: Provider<DownloadPreferences>,
    private val backupPreferences: Provider<BackupPreferences>,
    private val storagePreferences: Provider<StoragePreferences>,

    private val chapterCache: Provider<ChapterCache>,
    private val coverCache: Provider<CoverCache>,
    private val pagePreviewCache: Provider<PagePreviewCache>,
    private val downloadProvider: Provider<DownloadProvider>,
    private val trackerManager: Provider<TrackerManager>,
    private val delayedTrackingStore: Provider<DelayedTrackingStore>,
    private val imageSaver: Provider<ImageSaver>,

    private val basePreferences: Provider<BasePreferences>,
    private val uiPreferences: Provider<UiPreferences>,
    private val sourcePreferences: Provider<SourcePreferences>,
    private val readerPreferences: Provider<ReaderPreferences>,
    private val trackPreferences: Provider<TrackPreferences>,

    private val categoryRepository: Provider<CategoryRepository>,
    private val mangaRepository: Provider<MangaRepository>,
    private val customMangaInfoRepository: Provider<CustomMangaInfoRepository>,
    private val mangaMetadataRepository: Provider<MangaMetadataRepository>,
    private val chapterRepository: Provider<ChapterRepository>,
    private val historyRepository: Provider<HistoryRepository>,
    private val trackRepository: Provider<TrackRepository>,
    private val updatesRepository: Provider<UpdatesRepository>,
    private val stubSourceRepository: Provider<StubSourceRepository>,
    private val releaseService: Provider<ReleaseService>,
    private val extensionStoreService: Provider<ExtensionStoreService>,
    private val extensionStoreRepository: Provider<ExtensionStoreRepository>,
) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory { json() }
        addSingletonFactory { xml() }
        addSingletonFactory { protoBuf() }
        addSingletonFactory { database() }

        addSingletonFactory { preferenceStore() }
        addSingletonFactory { networkHelper() }
        addSingletonFactory { javaScriptEngine() }
        addSingletonFactory { storageManager() }
        addSingletonFactory { localSourceFileSystem() }
        addSingletonFactory { localCoverManager() }

        addSingletonFactory { networkPreferences() }
        addSingletonFactory { securityPreferences() }
        addSingletonFactory { privacyPreferences() }
        addSingletonFactory { libraryPreferences() }
        addSingletonFactory { upcomingPreferences() }
        addSingletonFactory { updatesPreferences() }
        addSingletonFactory { downloadPreferences() }
        addSingletonFactory { backupPreferences() }
        addSingletonFactory { storagePreferences() }

        addSingletonFactory { chapterCache() }
        addSingletonFactory { coverCache() }
        addSingletonFactory { pagePreviewCache() }
        addSingletonFactory { downloadProvider() }
        addSingletonFactory { trackerManager() }
        addSingletonFactory { delayedTrackingStore() }
        addSingletonFactory { imageSaver() }

        addSingletonFactory { basePreferences() }
        addSingletonFactory { uiPreferences() }
        addSingletonFactory { sourcePreferences() }
        addSingletonFactory { readerPreferences() }
        addSingletonFactory { trackPreferences() }

        addSingletonFactory { categoryRepository() }
        addSingletonFactory { mangaRepository() }
        addSingletonFactory { customMangaInfoRepository() }
        addSingletonFactory { mangaMetadataRepository() }
        addSingletonFactory { chapterRepository() }
        addSingletonFactory { historyRepository() }
        addSingletonFactory { trackRepository() }
        addSingletonFactory { updatesRepository() }
        addSingletonFactory { stubSourceRepository() }
        addSingletonFactory { releaseService() }
        addSingletonFactory { extensionStoreService() }
        addSingletonFactory { extensionStoreRepository() }
    }
}
