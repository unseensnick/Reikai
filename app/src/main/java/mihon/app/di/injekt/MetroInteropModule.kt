package mihon.app.di.injekt

import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import exh.eh.EHentaiUpdateHelper
import exh.pref.DelegateSourcePreferences
import exh.source.ExhPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.serialization.XML
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.track.NovelDelayedTrackingStore
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.repository.MangaMetadataRepository
import tachiyomi.domain.manga.repository.MangaRepository
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
 * Every entry is deferred and registered as a factory, never an eager instance, because
 * LegacyYokaiDbImporter has to move an incompatible database aside before anything opens it, and
 * it runs after this module is imported. See docs/dev/plans/legacy-yokai-import.md.
 */
@Inject
class MetroInteropModule(
    private val json: () -> Json,
    private val xml: () -> XML,
    private val protoBuf: () -> ProtoBuf,

    private val preferenceStore: () -> PreferenceStore,
    private val networkHelper: () -> NetworkHelper,
    private val javaScriptEngine: () -> JavaScriptEngine,

    private val libraryPreferences: () -> LibraryPreferences,

    private val coverCache: () -> CoverCache,
    private val trackerManager: () -> TrackerManager,

    private val basePreferences: () -> BasePreferences,
    private val sourcePreferences: () -> SourcePreferences,
    private val trackPreferences: () -> TrackPreferences,

    private val categoryRepository: () -> CategoryRepository,
    private val mangaRepository: () -> MangaRepository,
    private val mangaMetadataRepository: () -> MangaMetadataRepository,

    private val extensionManager: () -> ExtensionManager,

    private val delegateSourcePreferences: () -> DelegateSourcePreferences,
    private val exhPreferences: () -> ExhPreferences,
    private val reikaiLibraryPreferences: () -> ReikaiLibraryPreferences,
    private val novelPreferences: () -> NovelPreferences,

    private val novelRepository: () -> NovelRepository,
    private val novelChapterRepository: () -> NovelChapterRepository,
    private val novelHistoryRepository: () -> NovelHistoryRepository,
    private val novelTrackRepository: () -> NovelTrackRepository,

    private val lnPluginInstaller: () -> LnPluginInstaller,
    private val novelSourceManager: () -> NovelSourceManager,
    private val novelDownloadManager: () -> NovelDownloadManager,
    private val novelDelayedTrackingStore: () -> NovelDelayedTrackingStore,

    private val novelMergeManager: () -> NovelMergeManager,
    private val novelMergedChapterProvider: () -> NovelMergedChapterProvider,
    private val eHentaiUpdateHelper: () -> EHentaiUpdateHelper,
) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory { json() }
        addSingletonFactory { xml() }
        addSingletonFactory { protoBuf() }

        addSingletonFactory { preferenceStore() }
        addSingletonFactory { networkHelper() }
        addSingletonFactory { javaScriptEngine() }

        addSingletonFactory { libraryPreferences() }

        addSingletonFactory { coverCache() }
        addSingletonFactory { trackerManager() }

        addSingletonFactory { basePreferences() }
        addSingletonFactory { sourcePreferences() }
        addSingletonFactory { trackPreferences() }

        addSingletonFactory { categoryRepository() }
        addSingletonFactory { mangaRepository() }
        addSingletonFactory { mangaMetadataRepository() }

        addSingletonFactory { extensionManager() }

        addSingletonFactory { delegateSourcePreferences() }
        addSingletonFactory { exhPreferences() }
        addSingletonFactory { reikaiLibraryPreferences() }
        addSingletonFactory { novelPreferences() }

        addSingletonFactory { novelRepository() }
        addSingletonFactory { novelChapterRepository() }
        addSingletonFactory { novelHistoryRepository() }
        addSingletonFactory { novelTrackRepository() }

        addSingletonFactory { lnPluginInstaller() }
        addSingletonFactory { novelSourceManager() }
        addSingletonFactory { novelDownloadManager() }
        addSingletonFactory { novelDelayedTrackingStore() }

        addSingletonFactory { novelMergeManager() }
        addSingletonFactory { novelMergedChapterProvider() }
        addSingletonFactory { eHentaiUpdateHelper() }
    }
}
