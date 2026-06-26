package reikai.domain.source

import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager

/**
 * The installed light-novel sources minus the user-disabled ones, used by the novel global search to
 * exclude disabled sources. One place defines the [ReikaiSourcePreferences.disabledNovelSources]
 * filter; the Sources tab shows disabled sources dimmed (so they can be re-enabled) rather than
 * hiding them, so it intentionally does not apply this filter.
 */
class GetEnabledNovelSources(
    private val manager: NovelSourceManager,
    private val preferences: ReikaiSourcePreferences,
) {
    fun get(): List<NovelSource> =
        manager.getAll().filterNot { it.id in preferences.disabledNovelSources.get() }
}
