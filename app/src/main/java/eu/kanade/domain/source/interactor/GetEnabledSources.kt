package eu.kanade.domain.source.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import reikai.domain.source.ReikaiSourcePreferences
import reikai.domain.source.SourceKey
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Pins
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.source.local.isLocal

@Inject
class GetEnabledSources(
    private val repository: SourceRepository,
    private val preferences: SourcePreferences,
    // RK: the last-used source is one value across manga and novels, so it comes from Reikai's shared
    //     key rather than Mihon's manga-only one, which nothing writes any more.
    private val reikaiPreferences: ReikaiSourcePreferences,
) {

    fun subscribe(): Flow<List<Source>> {
        return combine(
            preferences.pinnedSources.changes(),
            preferences.enabledLanguages.changes(),
            preferences.disabledSources.changes(),
            // RK -->
            reikaiPreferences.lastUsedSource.changes(),
            // RK <--
            repository.getSources(),
        ) { pinnedSourceIds, enabledLanguages, disabledSources, lastUsedSource, sources ->
            sources
                .filter { it.lang in enabledLanguages || it.isLocal() }
                .filterNot { it.id.toString() in disabledSources }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .flatMap {
                    val flag = if ("${it.id}" in pinnedSourceIds) Pins.pinned else Pins.unpinned
                    val source = it.copy(pin = flag)
                    val toFlatten = mutableListOf(source)
                    // RK: only a manga last-used marks a row here; a novel one leaves this list
                    //     with no Last used section, which is the point of the shared key.
                    if (lastUsedSource is SourceKey.Manga && source.id == lastUsedSource.id) {
                        toFlatten.add(source.copy(isUsedLast = true, pin = source.pin - Pin.Actual))
                    }
                    toFlatten
                }
        }
            .distinctUntilChanged()
    }
}
