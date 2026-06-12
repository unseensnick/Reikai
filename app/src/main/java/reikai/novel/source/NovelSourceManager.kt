package reikai.novel.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory registry of installed [NovelSource]s, keyed by `source.id`. Mirrors the structural
 * shape of [eu.kanade.tachiyomi.source.SourceManager] but drops the extension-manager and
 * stub-source machinery: lnreader plugins are JS files loaded directly, not APKs class-loaded, so
 * there is no "source installed but binary not loaded yet" state to model.
 *
 * In-memory only: the installer (S2c) registers sources here on app start.
 */
class NovelSourceManager {

    private val sourcesFlow = MutableStateFlow<Map<String, NovelSource>>(emptyMap())

    val sources: Flow<List<NovelSource>> = sourcesFlow.map { it.values.toList() }

    fun register(source: NovelSource) {
        sourcesFlow.update { it + (source.id to source) }
    }

    fun unregister(id: String) {
        sourcesFlow.update { it - id }
    }

    fun get(id: String): NovelSource? = sourcesFlow.value[id]

    fun getAll(): List<NovelSource> = sourcesFlow.value.values.toList()
}
