package reikai.novel.source

import mihon.feature.migration.list.search.BaseSmartSearchEngine
import mihon.feature.migration.list.search.SearchAction
import reikai.novel.host.NovelItem

/**
 * Title-similarity matching for novel migration suggestions, over Mihon's engine, so both content
 * types score with the same normalized-Levenshtein threshold. Without it a suggestion is whatever the
 * plugin returned first, and accept-all would replace onto an unrelated title. One inherited limit,
 * shared with manga: the base engine skips scoring when a source returns a single candidate for a
 * single query, so a lone bad hit is still suggested unscored. Pass the raw hit list, never a
 * pre-filtered one, or dedupe can manufacture that case.
 */
class SmartNovelSearchEngine(extraSearchParams: String?) : BaseSmartSearchEngine<NovelItem>(extraSearchParams) {

    override fun getTitle(result: NovelItem) = result.name

    /**
     * The best-scoring hit, or null when nothing clears the threshold. [deep] runs Mihon's deep search,
     * which strips bracketed tags and searches word subsets of the title, as manga's does. [search] is
     * passed in rather than taking a source, because the caller also has to drop the entry's own
     * listing and the duplicate paths a plugin can repeat within one page.
     */
    suspend fun bestMatch(title: String, deep: Boolean = false, search: SearchAction<NovelItem>): NovelItem? =
        if (deep) deepSearch(search, title) else regularSearch(search, title)
}
