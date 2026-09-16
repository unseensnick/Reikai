package mihon.app.di.injekt

import android.app.Application
import android.content.Context
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.MetadataSource
import exh.eh.EHentaiUpdateHelper
import exh.pref.DelegateSourcePreferences
import exh.source.ExhPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.app.di.AppGraph
import mihon.core.metro.GraphProvider
import nl.adaptivity.xmlutil.serialization.XML
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.InjektionException
import uy.kohesive.injekt.api.TypeReference
import java.lang.reflect.Type

class MetroInjektRegistrar(
    private val application: Application,
    private val graphProvider: GraphProvider<AppGraph>,
) : InjektRegistrar {

    private val graph: AppGraph inline get() = graphProvider.graph

    private val bindings = mapOf<Type, () -> Any>(
        Application::class.java to { application },
        Context::class.java to { application },

        Json::class.java to { graph.json },
        ProtoBuf::class.java to { graph.protoBuf },
        XML::class.java to { graph.xml },

        NetworkHelper::class.java to { graph.networkHelper },
        JavaScriptEngine::class.java to { graph.javaScriptEngine },

        // RK --> Reikai's Injekt surface is wider than upstream's, because source-api carries the
        // adult subsystem's preferences and the MetadataSource contract, and the built-in MangaDex
        // and E-Hentai sources resolve their own dependencies the same way an extension would.
        // Every entry below has a reader; scripts/di-interop-check.ps1 fails on one that does not.
        TrackerManager::class.java to { graph.trackerManager },
        TrackPreferences::class.java to { graph.trackPreferences },

        DelegateSourcePreferences::class.java to { graph.delegateSourcePreferences },
        ExhPreferences::class.java to { graph.exhPreferences },
        EHentaiUpdateHelper::class.java to { graph.eHentaiUpdateHelper },

        // The interactors are unscoped, so each read builds a fresh instance. That is deliberate:
        // DomainModule registered all three with addFactory, never addSingletonFactory.
        MetadataSource.GetMangaId::class.java to { graph.getManga },
        MetadataSource.GetFlatMetadataById::class.java to { graph.getFlatMetadataById },
        MetadataSource.InsertFlatMetadata::class.java to { graph.insertFlatMetadata },
        // RK <--
    )

    override fun <R : Any> getInstance(forType: Type): R = getInstanceOrNull(forType)
        ?: throw InjektionException("$forType is not exposed to Injekt, add it to ${this::class.simpleName}")

    override fun <R : Any> getInstanceOrElse(forType: Type, default: R): R = getInstanceOrNull(forType) ?: default

    override fun <R : Any> getInstanceOrElse(forType: Type, default: () -> R): R =
        getInstanceOrNull(forType) ?: default()

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstanceOrNull(forType: Type): R? = bindings[forType]?.invoke() as R?

    override fun <R : Any, K : Any> getKeyedInstance(forType: Type, key: K): R = unsupported("Keyed injection")

    override fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: R): R =
        unsupported("Keyed injection")

    override fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: () -> R): R =
        unsupported("Keyed injection")

    @Suppress("RedundantNullableReturnType")
    override fun <R : Any, K : Any> getKeyedInstanceOrNull(forType: Type, key: K): R? = unsupported("Keyed injection")

    override fun <R : Any> getLogger(expectedLoggerType: Type, byName: String): R = unsupported("Logger injection")

    override fun <R : Any, T : Any> getLogger(expectedLoggerType: Type, forClass: Class<T>): R =
        unsupported("Logger injection")

    override fun <T : Any> addSingleton(forType: TypeReference<T>, singleInstance: T) = readOnly()

    override fun <R : Any> addSingletonFactory(forType: TypeReference<R>, factoryCalledOnce: () -> R) = readOnly()

    override fun <R : Any> addFactory(forType: TypeReference<R>, factoryCalledEveryTime: () -> R) = readOnly()

    override fun <R : Any> addPerThreadFactory(forType: TypeReference<R>, factoryCalledOncePerThread: () -> R) =
        readOnly()

    override fun <R : Any, K : Any> addPerKeyFactory(forType: TypeReference<R>, factoryCalledPerKey: (K) -> R) =
        readOnly()

    override fun <R : Any, K : Any> addPerThreadPerKeyFactory(
        forType: TypeReference<R>,
        factoryCalledPerKeyPerThread: (K) -> R,
    ) = readOnly()

    override fun <R : Any> addLoggerFactory(
        forLoggerType: TypeReference<R>,
        factoryByName: (String) -> R,
        factoryByClass: (Class<Any>) -> R,
    ) = readOnly()

    override fun <O : Any, T : O> addAlias(
        existingRegisteredType: TypeReference<T>,
        otherAncestorOrInterface: TypeReference<O>,
    ) = readOnly()

    override fun <T : Any> hasFactory(forType: TypeReference<T>): Boolean = forType.type in bindings

    override fun importModule(submodule: InjektModule) = readOnly()

    private fun readOnly(): Nothing = throw UnsupportedOperationException("Mihon's Injekt instance is read-only")

    private fun unsupported(feature: String): Nothing =
        throw UnsupportedOperationException("$feature is not supported by Mihon's Injekt instance")
}
