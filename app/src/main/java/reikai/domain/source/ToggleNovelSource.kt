package reikai.domain.source

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.preference.getAndSet

/** Turns novel sources on or off, the novel twin of `ToggleSource` keyed by text id, pinned by ToggleSourceConformanceTest. */
@Inject
class ToggleNovelSource(
    private val preferences: ReikaiSourcePreferences,
) {

    /** Flips [sourceId], or sets it to [enable] when given. */
    fun await(sourceId: String, enable: Boolean = sourceId in preferences.disabledNovelSources.get()) {
        await(listOf(sourceId), enable)
    }

    fun await(sourceIds: List<String>, enable: Boolean) {
        preferences.disabledNovelSources.getAndSet { if (enable) it - sourceIds.toSet() else it + sourceIds }
    }
}
