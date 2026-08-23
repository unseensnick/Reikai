package reikai.novel.source

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import reikai.novel.install.LnPluginInstaller

/**
 * In-memory registry of installed [NovelSource]s, keyed by `source.id`. Mirrors
 * [eu.kanade.tachiyomi.source.SourceManager] but drops the extension-manager and stub-source machinery: lnreader
 * plugins are JS loaded directly, not APKs class-loaded.
 *
 * Unlike the manga registry it cannot fill itself, because plugins have to be fetched and evaluated, so nothing
 * populates the map until something asks. [ensureLoaded] is that ask.
 */
@Inject
@SingleIn(AppScope::class)
class NovelSourceManager(
    // Deferred because the installer registers back into this map, so the two are a cycle; Metro cuts
    // it at the Provider, the same way ReikaiBindings cuts the merge managers' propagator edge.
    private val installer: Provider<LnPluginInstaller>,
) {

    private val sourcesFlow = MutableStateFlow<Map<String, NovelSource>>(emptyMap())

    val sources: Flow<List<NovelSource>> = sourcesFlow.map { it.values.toList() }

    private val _isInitialized = MutableStateFlow(false)

    /** False until [ensureLoaded] has completed once. Reading the registry before then reports every
     *  installed source as missing, which is a wrong answer rather than a slow one. */
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /** Load every installed plugin that is not registered yet, then return. Cheap and safe to call
     *  repeatedly: the installer holds its own mutex and retries only what failed. */
    suspend fun ensureLoaded() {
        installer().ensureLoaded()
        _isInitialized.value = true
    }

    fun register(source: NovelSource) {
        sourcesFlow.update { it + (source.id to source) }
    }

    fun unregister(id: String) {
        sourcesFlow.update { it - id }
    }

    fun get(id: String): NovelSource? = sourcesFlow.value[id]

    fun getAll(): List<NovelSource> = sourcesFlow.value.values.toList()
}
