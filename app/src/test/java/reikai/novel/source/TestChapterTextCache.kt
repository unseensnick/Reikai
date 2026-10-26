package reikai.novel.source

import android.content.Context
import eu.kanade.tachiyomi.data.cache.ChapterCache
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import java.nio.file.Files

/** The real cache over a fresh directory of its own, so no test reads text another one fetched. */
fun testChapterTextCache(chapterCache: ChapterCache = testChapterCache()): NovelChapterTextCache =
    NovelChapterTextCache(chapterCache, Json)

fun testChapterCache(): ChapterCache {
    val dir = Files.createTempDirectory("chapter_cache").toFile().apply { deleteOnExit() }
    val context = mockk<Context> { every { cacheDir } returns dir }
    return ChapterCache(context, Json)
}
