package yokai.novel.download

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.injectLazy
import yokai.domain.novel.NovelChapterRepository

/**
 * Persists the active novel-download queue across process restarts (mirrors the manga
 * [eu.kanade.tachiyomi.data.download.DownloadStore]). Only `{novelId, chapterId, order}` is stored;
 * the chapter url is re-read from the DB on [restore] so an interrupted batch resumes.
 */
class NovelDownloadStore(context: Context) {

    private val preferences = context.getSharedPreferences("active_novel_downloads", Context.MODE_PRIVATE)

    private val json: Json by injectLazy()
    private val chapterRepo: NovelChapterRepository by injectLazy()

    private var counter = 0

    val isEmpty: Boolean get() = preferences.all.isEmpty()

    fun addAll(downloads: List<NovelDownload>) {
        preferences.edit {
            downloads.forEach { putString(it.chapterId.toString(), serialize(it)) }
        }
    }

    fun remove(chapterId: Long) {
        preferences.edit { remove(chapterId.toString()) }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    /** Rebuild the queue from storage. Each entry's url is re-read from its chapter row; entries
     *  whose chapter no longer exists are dropped. Call off the main thread. */
    suspend fun restore(): List<NovelDownload> {
        val objs = preferences.all.values
            .mapNotNull { it as? String }
            .mapNotNull { deserialize(it) }
            .sortedBy { it.order }
        val out = mutableListOf<NovelDownload>()
        for (obj in objs) {
            val chapter = chapterRepo.getById(obj.chapterId) ?: continue
            out.add(NovelDownload(novelId = obj.novelId, chapterId = obj.chapterId, url = chapter.url))
        }
        return out
    }

    private fun serialize(d: NovelDownload): String =
        json.encodeToString(DownloadObject(d.novelId, d.chapterId, counter++))

    private fun deserialize(string: String): DownloadObject? = try {
        json.decodeFromString<DownloadObject>(string)
    } catch (e: Exception) {
        null
    }

    @Serializable
    private data class DownloadObject(val novelId: Long, val chapterId: Long, val order: Int)
}
