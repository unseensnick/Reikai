package mihon.app.di.injekt

import dev.zacsweers.metro.Inject
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
import tachiyomi.core.common.preference.PreferenceStore
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
 * instead of crashing. What earns a place: upstream's nine, plus what source-api and the app's own
 * hand-resolved readers need, plus their closure. A type nothing reads is a second registration
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

    private val coverCache: () -> CoverCache,
    private val trackerManager: () -> TrackerManager,
    private val trackPreferences: () -> TrackPreferences,

    private val mangaRepository: () -> MangaRepository,
    private val mangaMetadataRepository: () -> MangaMetadataRepository,

    private val extensionManager: () -> ExtensionManager,

    private val delegateSourcePreferences: () -> DelegateSourcePreferences,
    private val exhPreferences: () -> ExhPreferences,
    private val eHentaiUpdateHelper: () -> EHentaiUpdateHelper,
) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory { json() }
        addSingletonFactory { xml() }
        addSingletonFactory { protoBuf() }

        addSingletonFactory { preferenceStore() }
        addSingletonFactory { networkHelper() }
        addSingletonFactory { javaScriptEngine() }

        addSingletonFactory { coverCache() }
        addSingletonFactory { trackerManager() }
        addSingletonFactory { trackPreferences() }

        addSingletonFactory { mangaRepository() }
        addSingletonFactory { mangaMetadataRepository() }

        addSingletonFactory { extensionManager() }

        addSingletonFactory { delegateSourcePreferences() }
        addSingletonFactory { exhPreferences() }
        addSingletonFactory { eHentaiUpdateHelper() }
    }
}
