package reikai.novel.source

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.novel.install.LnPluginInstaller
import tachiyomi.core.common.util.system.logcat

/**
 * In-memory registry of installed [NovelSource]s, keyed by `source.id`, over both kinds: LNReader
 * plugins and the catalogues of novel extension apps.
 *
 * The apps it follows itself, as the manga registry follows its extensions. The plugins it cannot,
 * because they have to be fetched and evaluated, so nothing loads them until something asks.
 * [ensureLoaded] is that ask.
 */
@Inject
@SingleIn(AppScope::class)
class NovelSourceManager(
    // Deferred because the installer registers back into this map, so the two are a cycle; Metro cuts
    // it at the deferred parameter, the same way ReikaiBindings cuts the merge managers' propagator edge.
    private val installer: () -> LnPluginInstaller,
    extensionManager: ExtensionManager,
    private val prefs: NovelPreferences,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sourcesFlow = MutableStateFlow<Map<String, NovelSource>>(emptyMap())

    val sources: Flow<List<NovelSource>> = sourcesFlow.map { it.values.toList() }

    /** Completes once the installed apps have been registered for the first time. */
    private val appsRegistered = CompletableDeferred<Unit>()

    private val seenMutex = Mutex()

    init {
        scope.launch {
            extensionManager.loadedNovelExtensionsFlow.collect { extensions ->
                try {
                    registerApps(extensions)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Could not register the novel extension apps" }
                } finally {
                    // Released whatever happened, or every source lookup would wait on it forever.
                    appsRegistered.complete(Unit)
                }
            }
        }
    }

    /** Load every installed plugin that is not registered yet, then return. Cheap and safe to call
     *  repeatedly: the installer holds its own mutex and retries only what failed. */
    suspend fun ensureLoaded() {
        installer().ensureLoaded()
        appsRegistered.await()
    }

    fun register(source: NovelSource) {
        sourcesFlow.update { it + (source.id to source) }
    }

    fun unregister(id: String) {
        sourcesFlow.update { it - id }
    }

    /**
     * Loads the plugins before answering, so a read before the first [ensureLoaded] gets a slow
     * answer rather than a wrong one. Suspending matches the manga registry, which upstream made
     * suspend for the same reason (mihonapp/mihon#3869).
     */
    suspend fun get(id: String): NovelSource? {
        ensureLoaded()
        return sourcesFlow.value[id]
    }

    suspend fun getAll(): List<NovelSource> {
        ensureLoaded()
        return sourcesFlow.value.values.toList()
    }

    /**
     * Merges each source's identity into the record kept after it is removed, which is how a novel whose
     * source was uninstalled still shows a name. One lock for both kinds, since each rewrites the map.
     */
    suspend fun rememberSeen(identities: Map<String, LnSourceIdentity>) {
        if (identities.isEmpty()) return
        seenMutex.withLock {
            val current = prefs.seenNovelSources().get()
            val updated = current + identities
            if (updated != current) prefs.seenNovelSources().set(updated)
        }
    }

    /** The loaded apps' catalogues replace the ones registered before, keeping any that did not change. */
    private suspend fun registerApps(extensions: List<Extension.Loaded>) {
        val registered = sourcesFlow.value.values.filterIsInstance<TachiyomiNovelSource>().associateBy { it.id }
        val adapters = extensions.flatMap { extension ->
            extension.sources.filterIsInstance<CatalogueSource>().mapNotNull { catalogue ->
                registered[TACHIYOMI_NOVEL_SOURCE_PREFIX + catalogue.id]?.takeIf { it.source === catalogue }
                    // One catalogue failing to build must not keep the app's others out.
                    ?: runCatching { TachiyomiNovelSource(catalogue, extension) }
                        .onFailure { logcat(LogPriority.ERROR, it) { "Could not load ${extension.pkgName}" } }
                        .getOrNull()
            }
        }
        sourcesFlow.update { current ->
            current.filterValues { it !is TachiyomiNovelSource } + adapters.associateBy { it.id }
        }
        // The icon is left out: its address names the app, and dies with it.
        rememberSeen(adapters.associate { it.id to LnSourceIdentity(name = it.name, lang = it.lang, site = it.site) })
    }
}
