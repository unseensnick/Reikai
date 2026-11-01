package reikai.novel.source

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import mihon.data.cache.ChapterCache
import reikai.util.testAppScope
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The real cache over a fresh directory of its own, so no test reads text another one fetched. */
fun testChapterTextCache(chapterCache: ChapterCache = testChapterCache()): NovelChapterTextCache =
    NovelChapterTextCache(chapterCache, Json)

fun testChapterCache(
    scope: CoroutineScope = testAppScope(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    saveDelay: Duration = 2.seconds,
): ChapterCache {
    val dir = Files.createTempDirectory("chapter_cache").toFile().apply { deleteOnExit() }
    val context = mockk<Context> {
        every { cacheDir } returns dir
        every { noBackupFilesDir } returns dir
    }
    return ChapterCache(context, Json, scope, dispatcher, saveDelay)
}
