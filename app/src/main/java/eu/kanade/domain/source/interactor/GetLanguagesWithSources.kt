package eu.kanade.domain.source.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import reikai.presentation.browse.sourcesFilterLanguageOrder
import reikai.presentation.browse.sourcesFilterSourceOrder
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository
import java.util.SortedMap

@Inject
class GetLanguagesWithSources(
    private val repository: SourceRepository,
    private val preferences: SourcePreferences,
) {

    fun subscribe(): Flow<SortedMap<String, List<Source>>> {
        return combine(
            preferences.enabledLanguages.changes(),
            preferences.disabledSources.changes(),
            repository.getOnlineSources(),
        ) { enabledLanguage, disabledSource, onlineSources ->
            // RK --> the sources filter's order, which the novel half of the screen sorts by too
            val sortedSources = onlineSources.sortedWith(
                sourcesFilterSourceOrder({ it.id.toString() in disabledSource }, Source::name),
            )

            sortedSources
                .groupBy { it.lang }
                .toSortedMap(sourcesFilterLanguageOrder { it !in enabledLanguage })
            // RK <--
        }
    }
}
