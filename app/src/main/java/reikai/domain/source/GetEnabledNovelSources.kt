package reikai.domain.source

import dev.zacsweers.metro.Inject
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.novel.source.isDisabled

/**
 * The installed light-novel sources the user has not switched off, per source or per language, for
 * global search, the feed and novel migration. The Sources tab applies the same [isDisabled] rule to
 * its own list, and the filter screen is where both are turned back on.
 */
@Inject
class GetEnabledNovelSources(
    private val manager: NovelSourceManager,
    private val preferences: ReikaiSourcePreferences,
) {
    suspend fun get(): List<NovelSource> {
        val disabledSources = preferences.disabledNovelSources.get()
        val disabledLanguages = preferences.disabledNovelLanguages.get()
        return manager.getAll().filterNot { it.isDisabled(disabledSources, disabledLanguages) }
    }
}
