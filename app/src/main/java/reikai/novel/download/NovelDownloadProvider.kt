package reikai.novel.download

import com.hippo.unifile.UniFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.injectLazy

/**
 * Resolves on-disk locations for downloaded novel chapter text. Path scheme:
 * `<novel downloads dir>/<novelId>/<chapterId>.html`. `chapterId` is a globally-unique DB primary
 * key, so the source/plugin id is not part of the path (unlike the manga
 * [eu.kanade.tachiyomi.data.download.DownloadProvider], which keys by source + manga + chapter).
 * Text-only: one self-contained HTML file per chapter (inline images are embedded as `data:` URIs at
 * write time), no page dirs or archives.
 *
 * Tracks the user's chosen storage base via [StorageManager.changes], so moving storage repoints
 * future writes (existing files don't migrate, matching the manga side).
 */
class NovelDownloadProvider {

    private val storageManager: StorageManager by injectLazy()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var downloadsDir = storageManager.getNovelDownloadsDirectory()

    init {
        storageManager.changes.onEach {
            downloadsDir = storageManager.getNovelDownloadsDirectory()
        }.launchIn(scope)
    }

    private fun novelDir(novelId: Long): UniFile? = downloadsDir?.createDirectory(novelId.toString())

    private fun findNovelDir(novelId: Long): UniFile? = downloadsDir?.findFile(novelId.toString())

    fun isChapterDownloaded(novelId: Long, chapterId: Long): Boolean =
        findChapterFile(novelId, chapterId)?.exists() == true

    fun readChapter(novelId: Long, chapterId: Long): String? =
        findChapterFile(novelId, chapterId)?.takeIf { it.exists() }
            ?.openInputStream()?.bufferedReader()?.use { it.readText() }

    /** Persist a chapter's HTML, creating parent dirs. False when no storage dir is configured. */
    fun writeChapter(novelId: Long, chapterId: Long, html: String): Boolean {
        val file = novelDir(novelId)?.createFile("$chapterId.html") ?: return false
        file.openOutputStream().bufferedWriter().use { it.write(html) }
        return true
    }

    fun deleteChapter(novelId: Long, chapterId: Long) {
        findChapterFile(novelId, chapterId)?.delete()
    }

    fun deleteNovel(novelId: Long) {
        findNovelDir(novelId)?.delete()
    }

    private fun findChapterFile(novelId: Long, chapterId: Long): UniFile? =
        findNovelDir(novelId)?.findFile("$chapterId.html")
}
