package eu.kanade.domain.extension.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import reikai.domain.source.ReikaiSourcePreferences // RK
import reikai.novel.source.novelSourceId // RK

@Inject
class GetExtensionSources(
    private val preferences: SourcePreferences,
    private val reikaiSourcePreferences: ReikaiSourcePreferences, // RK
) {

    fun subscribe(extension: Extension.Loaded): Flow<List<ExtensionSourceItem>> {
        val isMultiSource = extension.sources.size > 1
        val isMultiLangSingleSource =
            isMultiSource && extension.sources.map { it.name }.distinct().size == 1

        // RK --> a novel app's sources are switched on the novel list, by their text id
        val isManga = extension.kind == Extension.Kind.MANGA
        val disabled = if (isManga) preferences.disabledSources else reikaiSourcePreferences.disabledNovelSources
        return disabled.changes().map { disabledSources ->
            fun Source.isEnabled() =
                (extension.kind.novelSourceId(id) ?: id.toString()) !in disabledSources
            // RK <--

            extension.sources
                .map { source ->
                    ExtensionSourceItem(
                        source = source,
                        enabled = source.isEnabled(),
                        labelAsName = isMultiSource && !isMultiLangSingleSource,
                    )
                }
        }
    }
}

data class ExtensionSourceItem(
    val source: Source,
    val enabled: Boolean,
    val labelAsName: Boolean,
)
