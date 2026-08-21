package mihon.app.di.injekt

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import exh.eh.EHentaiUpdateHelper
import exh.pref.DelegateSourcePreferences
import exh.source.ExhPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.domain.extension.repository.ExtensionStoreRepository
import nl.adaptivity.xmlutil.serialization.XML
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.track.NovelDelayedTrackingStore
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadProvider
import reikai.novel.host.LnPluginHost
import reikai.novel.host.LnPluginLoader
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.repository.MangaMetadataRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory

/**
 * Hands Metro-owned singletons back to Injekt, which stays as the runtime facade installed
 * extensions resolve against.
 *
 * A type listed here must have its registration deleted from the Injekt modules in the same
 * change. Registered in both places, the app runs with two instances and loses state silently
 * instead of crashing. What earns a place: upstream's nine, plus what source-api and the novel
 * reader resolve by hand, plus their closure. A type nothing reads is a second registration
 * surface rather than headroom, so it goes; scripts/di-interop-check.ps1 names those.
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

    private val preferenceStore: Provider<PreferenceStore>,
    private val networkHelper: Provider<NetworkHelper>,
    private val javaScriptEngine: Provider<JavaScriptEngine>,
    private val storageManager: Provider<StorageManager>,

    private val networkPreferences: Provider<NetworkPreferences>,
    private val securityPreferences: Provider<SecurityPreferences>,
    private val libraryPreferences: Provider<LibraryPreferences>,
    private val downloadPreferences: Provider<DownloadPreferences>,
    private val storagePreferences: Provider<StoragePreferences>,

    private val coverCache: Provider<CoverCache>,
    private val downloadProvider: Provider<DownloadProvider>,
    private val trackerManager: Provider<TrackerManager>,

    private val basePreferences: Provider<BasePreferences>,
    private val sourcePreferences: Provider<SourcePreferences>,
    private val readerPreferences: Provider<ReaderPreferences>,
    private val trackPreferences: Provider<TrackPreferences>,

    private val categoryRepository: Provider<CategoryRepository>,
    private val mangaRepository: Provider<MangaRepository>,
    private val mangaMetadataRepository: Provider<MangaMetadataRepository>,
    private val extensionStoreRepository: Provider<ExtensionStoreRepository>,

    private val extensionManager: Provider<ExtensionManager>,
    private val downloadManager: Provider<DownloadManager>,

    private val delegateSourcePreferences: Provider<DelegateSourcePreferences>,
    private val exhPreferences: Provider<ExhPreferences>,
    private val reikaiLibraryPreferences: Provider<ReikaiLibraryPreferences>,
    private val reikaiSourcePreferences: Provider<ReikaiSourcePreferences>,
    private val novelPreferences: Provider<NovelPreferences>,

    private val mergeGroupRepository: Provider<MergeGroupRepository>,
    private val novelRepository: Provider<NovelRepository>,
    private val novelChapterRepository: Provider<NovelChapterRepository>,
    private val novelHistoryRepository: Provider<NovelHistoryRepository>,
    private val novelTrackRepository: Provider<NovelTrackRepository>,

    private val lnPluginHost: Provider<LnPluginHost>,
    private val lnPluginLoader: Provider<LnPluginLoader>,
    private val lnPluginInstaller: Provider<LnPluginInstaller>,
    private val novelSourceManager: Provider<NovelSourceManager>,
    private val novelDownloadProvider: Provider<NovelDownloadProvider>,
    private val novelDownloadCache: Provider<NovelDownloadCache>,
    private val novelDownloadManager: Provider<NovelDownloadManager>,
    private val novelDelayedTrackingStore: Provider<NovelDelayedTrackingStore>,

    private val novelMergeManager: Provider<NovelMergeManager>,
    private val eHentaiUpdateHelper: Provider<EHentaiUpdateHelper>,
) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory { json() }
        addSingletonFactory { xml() }
        addSingletonFactory { protoBuf() }

        addSingletonFactory { preferenceStore() }
        addSingletonFactory { networkHelper() }
        addSingletonFactory { javaScriptEngine() }
        addSingletonFactory { storageManager() }

        addSingletonFactory { networkPreferences() }
        addSingletonFactory { securityPreferences() }
        addSingletonFactory { libraryPreferences() }
        addSingletonFactory { downloadPreferences() }
        addSingletonFactory { storagePreferences() }

        addSingletonFactory { coverCache() }
        addSingletonFactory { downloadProvider() }
        addSingletonFactory { trackerManager() }

        addSingletonFactory { basePreferences() }
        addSingletonFactory { sourcePreferences() }
        addSingletonFactory { readerPreferences() }
        addSingletonFactory { trackPreferences() }

        addSingletonFactory { categoryRepository() }
        addSingletonFactory { mangaRepository() }
        addSingletonFactory { mangaMetadataRepository() }
        addSingletonFactory { extensionStoreRepository() }

        addSingletonFactory { extensionManager() }
        addSingletonFactory { downloadManager() }

        addSingletonFactory { delegateSourcePreferences() }
        addSingletonFactory { exhPreferences() }
        addSingletonFactory { reikaiLibraryPreferences() }
        addSingletonFactory { reikaiSourcePreferences() }
        addSingletonFactory { novelPreferences() }

        addSingletonFactory { mergeGroupRepository() }
        addSingletonFactory { novelRepository() }
        addSingletonFactory { novelChapterRepository() }
        addSingletonFactory { novelHistoryRepository() }
        addSingletonFactory { novelTrackRepository() }

        addSingletonFactory { lnPluginHost() }
        addSingletonFactory { lnPluginLoader() }
        addSingletonFactory { lnPluginInstaller() }
        addSingletonFactory { novelSourceManager() }
        addSingletonFactory { novelDownloadProvider() }
        addSingletonFactory { novelDownloadCache() }
        addSingletonFactory { novelDownloadManager() }
        addSingletonFactory { novelDelayedTrackingStore() }

        addSingletonFactory { novelMergeManager() }
        addSingletonFactory { eHentaiUpdateHelper() }
    }
}
