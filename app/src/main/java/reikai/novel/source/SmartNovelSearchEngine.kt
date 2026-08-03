package reikai.novel.source

import mihon.feature.migration.list.search.BaseSmartSearchEngine
import mihon.feature.migration.list.search.SearchAction
import reikai.novel.host.NovelItem

/**
 * Title-similarity matching for novel migration suggestions, over Mihon's engine (generic in the
 * result type, so both content types score with the same normalized-Levenshtein threshold).
 *
 * Without it a suggestion is whatever the plugin happened to return first, and accept-all would
 * commit a replace onto an unrelated title.
 *
 * One inherited limit, shared with manga: the base engine skips scoring when a source returns a
 * single candidate for a single query, so a lone bad hit is still suggested unscored. Pass the raw
 * hit list, never a pre-filtered one, or dedupe can manufacture that case.
 *
 * Regular search only. Deep search fans a cleaned title out into five queries, one plugin round trip
 * each, and stays manga-gated until the tuning sheet offers it for novels.
 */
class SmartNovelSearchEngine(extraSearchParams: String?) : BaseSmartSearchEngine<NovelItem>(extraSearchParams) {

    override fun getTitle(result: NovelItem) = result.name

    /**
     * The best-scoring hit, or null when nothing clears the threshold. [search] is passed in rather
     * than taking a source, because the caller also has to drop the entry's own listing and the
     * duplicate paths a plugin can repeat within one page.
     */
    suspend fun bestMatch(title: String, search: SearchAction<NovelItem>): NovelItem? = regularSearch(search, title)
}
