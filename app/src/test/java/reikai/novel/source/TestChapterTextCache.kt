package reikai.novel.source

import android.content.Context
import eu.kanade.tachiyomi.data.cache.ChapterCache
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import java.nio.file.Files

/** The real cache over a fresh directory of its own, so no test reads text another one fetched. */
fun testChapterTextCache(): NovelChapterTextCache {
    val dir = Files.createTempDirectory("chapter_cache").toFile().apply { deleteOnExit() }
    val context = mockk<Context> { every { cacheDir } returns dir }
    return NovelChapterTextCache(ChapterCache(context, Json), Json)
}
