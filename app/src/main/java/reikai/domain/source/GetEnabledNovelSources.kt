package reikai.domain.source

import dev.zacsweers.metro.Inject
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager

/**
 * The installed light-novel sources minus the user-disabled ones (per source and per language),
 * used by the novel global search to exclude them. One place defines the
 * [ReikaiSourcePreferences.disabledNovelSources] + [ReikaiSourcePreferences.disabledNovelLanguages]
 * filter; the Sources tab applies the same filter through its own screen model, and the filter
 * screen is where both are re-enabled.
 */
@Inject
class GetEnabledNovelSources(
    private val manager: NovelSourceManager,
    private val preferences: ReikaiSourcePreferences,
) {
    fun get(): List<NovelSource> {
        val disabledSources = preferences.disabledNovelSources.get()
        val disabledLanguages = preferences.disabledNovelLanguages.get()
        return manager.getAll().filterNot { it.id in disabledSources || it.lang in disabledLanguages }
    }
}
