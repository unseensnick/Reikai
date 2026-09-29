package reikai.novel.download

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.core.migration.Migrator
import reikai.domain.download.QueuedChapter
import reikai.domain.novel.NovelChapterRepository

/**
 * Persists the active novel-download queue across process restarts (mirrors the manga
 * [eu.kanade.tachiyomi.data.download.DownloadStore]). Only `{novelId, chapterId, order}` is stored;
 * the chapter url is re-read from the DB on [restore] so an interrupted batch resumes.
 */
class NovelDownloadStore(
    context: Context,
    private val chapterRepo: NovelChapterRepository,
) {

    private val preferences = context.getSharedPreferences("active_novel_downloads", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    // Seed from the highest persisted order so a new enqueue after a process restart can't reuse
    // order values that collide with the restored queue (which would make the resume order arbitrary).
    private var counter = preferences.all.values
        .mapNotNull { (it as? String)?.let(::deserialize)?.order }
        .maxOrNull()?.plus(1) ?: 0

    fun addAll(downloads: List<NovelDownload>) {
        preferences.edit {
            downloads.forEach { putString(it.chapterId.toString(), serialize(it)) }
        }
    }

    /** Re-persist the whole queue in a new order (drag-to-reorder / sort). Clears then rewrites each
     *  entry with an ascending [DownloadObject.order] matching the list sequence, so a cold restart
     *  drains in this order. One edit, so the order numbers stay contiguous. */
    fun replaceAll(downloads: List<NovelDownload>) {
        preferences.edit {
            clear()
            downloads.forEach { putString(it.chapterId.toString(), serialize(it)) }
        }
    }

    fun remove(chapterId: Long) {
        preferences.edit { remove(chapterId.toString()) }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    /** Rebuild the queue from storage. Each entry's url is re-read from its chapter row; entries whose
     *  chapter no longer exists are dropped. Call off the main thread. */
    suspend fun restore(): List<NovelDownload> {
        // MergedDuplicateDownloads re-points this queue in a migration, which can still be running as the manager
        // that calls this is built
        Migrator.await()
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

    /** The saved rows as they are, for [reikai.data.dedupe.MergedDuplicateDownloads] after the upgrade's dedupe. */
    fun persisted(): List<QueuedChapter> = preferences.all.values
        .mapNotNull { (it as? String)?.let(::deserialize) }
        .map { QueuedChapter(it.novelId, it.chapterId, it.order) }

    fun replacePersisted(removed: List<QueuedChapter>, rows: List<QueuedChapter>) {
        preferences.edit {
            removed.forEach { remove(it.chapterId.toString()) }
            rows.forEach {
                putString(
                    it.chapterId.toString(),
                    json.encodeToString(DownloadObject(it.entryId, it.chapterId, it.order)),
                )
            }
        }
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
