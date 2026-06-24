package eu.kanade.tachiyomi.source

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.online.HttpSource
// RK -->
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.source.online.all.Lanraragi
import eu.kanade.tachiyomi.source.online.all.NHentai
import eu.kanade.tachiyomi.source.online.english.EightMuses
import eu.kanade.tachiyomi.source.online.english.Pururin
import exh.source.DelegatedHttpSource
import exh.source.EHENTAI_EXT_SOURCES
import exh.source.EIGHTMUSES_SOURCE_ID
import exh.source.EXHENTAI_EXT_SOURCES
import exh.source.EnhancedHttpSource
import exh.source.ExhPreferences
import exh.source.PURURIN_SOURCE_ID
// RK <--
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap

class AndroidSourceManager(
    private val context: Context,
    private val extensionManager: ExtensionManager,
    private val sourceRepository: StubSourceRepository,
) : SourceManager {

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val downloadManager: DownloadManager by injectLazy()

    // RK: gates the built-in E-Hentai / ExHentai sources.
    private val exhPreferences: ExhPreferences by injectLazy()

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    private val sourcesMapFlow = MutableStateFlow(ConcurrentHashMap<Long, Source>())

    private val stubSourcesMap = ConcurrentHashMap<Long, StubSource>()

    override val sources: Flow<List<Source>> = sourcesMapFlow.map { it.values.toList() }

    init {
        scope.launch {
            extensionManager.installedExtensionsFlow
                // RK: re-collect whenever the EXH gates flip so the built-in EH/ExH sources
                //     appear or disappear without an app restart.
                .combine(exhPreferences.enableExhentai().changes()) { extensions, enableExhentai ->
                    extensions to enableExhentai
                }
                .combine(exhPreferences.isHentaiEnabled().changes()) { (extensions, enableExhentai), isHentaiEnabled ->
                    Triple(extensions, enableExhentai, isHentaiEnabled)
                }
                .collectLatest { (extensions, enableExhentai, isHentaiEnabled) ->
                    val mutableMap = ConcurrentHashMap<Long, Source>(
                        mapOf(
                            LocalSource.ID to LocalSource(
                                context,
                                Injekt.get(),
                                Injekt.get(),
                            ),
                        ),
                    ).apply {
                        // RK: register the built-in E-Hentai sources (one per language) when the
                        //     hentai gate is on; ExHentai needs the additional login gate.
                        if (isHentaiEnabled) {
                            EHENTAI_EXT_SOURCES.forEach { (id, lang) ->
                                put(id, EHentai(id, false, context, lang))
                            }
                            if (enableExhentai) {
                                EXHENTAI_EXT_SOURCES.forEach { (id, lang) ->
                                    put(id, EHentai(id, true, context, lang))
                                }
                            }
                        }
                    }
                    extensions.forEach { extension ->
                        extension.sources.forEach {
                            // RK: wrap delegated adult sources so installed galleries gain metadata
                            mutableMap[it.id] = it.toEnhancedSource()
                            registerStubSource(StubSource.from(it))
                        }
                    }
                    sourcesMapFlow.value = mutableMap
                    _isInitialized.value = true
                }
        }

        scope.launch {
            sourceRepository.subscribeAll()
                .collectLatest { sources ->
                    val mutableMap = stubSourcesMap.toMutableMap()
                    sources.forEach {
                        mutableMap[it.id] = it
                    }
                }
        }
    }

    override fun get(sourceKey: Long): Source? {
        return sourcesMapFlow.value[sourceKey]
    }

    override fun getOrStub(sourceKey: Long): Source {
        return sourcesMapFlow.value[sourceKey] ?: stubSourcesMap.getOrPut(sourceKey) {
            runBlocking { createStubSource(sourceKey) }
        }
    }

    override fun getAll() = sourcesMapFlow.value.values.toList()

    override fun getOnlineSources() = sourcesMapFlow.value.values.filterIsInstance<HttpSource>()

    override fun getStubSources(): List<StubSource> {
        val onlineSourceIds = getOnlineSources().map { it.id }
        return stubSourcesMap.values.filterNot { it.id in onlineSourceIds }
    }

    private fun registerStubSource(source: StubSource) {
        scope.launch {
            val dbSource = sourceRepository.getStubSource(source.id)
            if (dbSource == source) return@launch
            sourceRepository.upsertStubSource(source.id, source.lang, source.name)
            if (dbSource != null) {
                downloadManager.renameSource(dbSource, source)
            }
        }
    }

    private suspend fun createStubSource(id: Long): StubSource {
        sourceRepository.getStubSource(id)?.let {
            return it
        }
        extensionManager.getSourceData(id)?.let {
            registerStubSource(it)
            return it
        }
        return StubSource(id = id, lang = "", name = "")
    }

    // RK -->
    // If an installed extension is in DELEGATED_SOURCES, wrap it in an EnhancedHttpSource so its
    // galleries gain searchable tag/title metadata; otherwise return the source unchanged.
    private fun Source.toEnhancedSource(): Source {
        val sourceQName = this::class.qualifiedName ?: return this
        val delegate = DELEGATED_SOURCES[sourceQName]
            ?: DELEGATED_SOURCES.values.find {
                it.factory && sourceQName.startsWith(it.originalSourceQualifiedClassName)
            }
        return if (this is HttpSource && delegate != null) {
            EnhancedHttpSource(this, delegate.newSourceFactory(this, context))
        } else {
            this
        }
    }

    companion object {
        private const val fillInSourceId = Long.MAX_VALUE

        // Installed extensions that get wrapped in a metadata-enhancing EnhancedHttpSource.
        // factory = true matches by package prefix (multi-language factory extensions).
        private val DELEGATED_SOURCES = listOf(
            DelegatedSource(
                "Pururin",
                PURURIN_SOURCE_ID,
                "eu.kanade.tachiyomi.extension.en.pururin.Pururin",
                ::Pururin,
            ),
            DelegatedSource(
                "8Muses",
                EIGHTMUSES_SOURCE_ID,
                "eu.kanade.tachiyomi.extension.en.eightmuses.EightMuses",
                ::EightMuses,
            ),
            DelegatedSource(
                "NHentai",
                fillInSourceId,
                "eu.kanade.tachiyomi.extension.all.nhentai.NHentai",
                ::NHentai,
                factory = true,
            ),
            DelegatedSource(
                "LANraragi",
                fillInSourceId,
                "eu.kanade.tachiyomi.extension.all.lanraragi.LANraragi",
                ::Lanraragi,
                factory = true,
            ),
        ).associateBy { it.originalSourceQualifiedClassName }

        private data class DelegatedSource(
            val sourceName: String,
            val sourceId: Long,
            val originalSourceQualifiedClassName: String,
            val newSourceFactory: (HttpSource, Context) -> DelegatedHttpSource,
            val factory: Boolean = false,
        )
    }
    // RK <--
}
