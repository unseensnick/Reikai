package reikai.novel.source

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.ChapterCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import reikai.domain.novel.model.NovelChapter
import tachiyomi.core.common.util.system.logcat

/**
 * A chapter's text as its source answered it, kept on disk so opening the chapter again skips the
 * network and the plugin, as manga's page lists are. It lives in manga's [ChapterCache], so that
 * cache's size cap and "Clear chapter cache" cover novels too. The text is stored before the content
 * pipeline runs, so a reading setting changed since still reaches it.
 */
@Inject
class NovelChapterTextCache(
    private val chapterCache: ChapterCache,
    private val json: Json,
) {

    /** Null when the chapter was never fetched, was evicted, or its entry cannot be read. */
    fun get(chapter: NovelChapter): CachedChapterText? = runCatching {
        chapterCache.getText(key(chapter))?.let { json.decodeFromString<CachedChapterText>(it) }
    }.getOrNull()

    /** A failed write costs only the next open's speed, so it is logged rather than failing the read. */
    fun put(chapter: NovelChapter, text: CachedChapterText) {
        runCatching { chapterCache.putText(key(chapter), json.encodeToString(text)) }
            .onFailure { logcat(LogPriority.WARN, it) { "Failed to cache novel chapter text" } }
    }

    // Prefixed, since a novel id and a manga id can be the same number in the cache they share.
    private fun key(chapter: NovelChapter) = "novel/${chapter.novelId}${chapter.url}"
}

/** [baseUrl] is the source site the text's relative links resolve against. */
@Serializable
data class CachedChapterText(val text: String, val baseUrl: String?)
