package reikai.novel.source

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.data.cache.ChapterCache
import reikai.domain.novel.model.NovelChapter
import tachiyomi.core.common.util.system.logcat

/**
 * A chapter's text as its source answered it, kept on disk so opening the chapter again skips the
 * network and the plugin, as manga's page lists are. It lives in manga's [ChapterCache], so
 * "Clear chapter cache" covers novels too. The text is stored before the content
 * pipeline runs, so a reading setting changed since still reaches it.
 */
@Inject
class NovelChapterTextCache(
    private val chapterCache: ChapterCache,
    private val json: Json,
) {

    /** Null when the chapter was never fetched, was evicted, or its entry cannot be read. */
    suspend fun get(chapter: NovelChapter): CachedChapterText? = try {
        chapterCache.getText(key(chapter))?.let { json.decodeFromString<CachedChapterText>(it) }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        logcat(LogPriority.WARN, e) { "Failed to read cached novel chapter text" }
        null
    }

    /** A failed write costs only the next open's speed, so it is logged rather than failing the read. */
    suspend fun put(chapter: NovelChapter, text: CachedChapterText) {
        try {
            chapterCache.putText(key(chapter), json.encodeToString(text))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "Failed to cache novel chapter text" }
        }
    }

    suspend fun remove(chapter: NovelChapter) {
        chapterCache.removeText(key(chapter))
    }

    // Prefixed, since a novel id and a manga id can be the same number in the cache they share. The slash after the id
    // keeps novel 1's path "2/ch" and novel 12's "/ch" apart.
    private fun key(chapter: NovelChapter) = "novel/${chapter.novelId}/${chapter.url}"
}

/** [baseUrl] is the source site the text's relative links resolve against. */
@Serializable
data class CachedChapterText(val text: String, val baseUrl: String?)
