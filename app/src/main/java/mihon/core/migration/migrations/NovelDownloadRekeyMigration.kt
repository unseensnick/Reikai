package mihon.core.migration.migrations

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.dedupe.MergedDuplicateRepository
import reikai.domain.dedupe.survivorIds
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadProvider
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager

/**
 * One-time relocation of novel downloads from the old numeric-id scheme
 * (`novel_downloads/<novelId>/<chapterId>.html`) to the stable-name scheme
 * (`novel_downloads/<source>/<title>/<chapter>_<hash>.html`) that [NovelDownloadProvider] now uses.
 *
 * Without it, an upgrader's existing downloads would look missing (the disk scan reads the new paths)
 * and re-download as duplicates. Best-effort per file: a chapter/novel row that can't be resolved is
 * left in place (no data loss), and a failed move leaves the old file for the user to re-download.
 * File I/O inside the migrations, which MainActivity blocks on, but each file is one chapter's HTML.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class NovelDownloadRekeyMigration(
    private val storageManager: StorageManager,
    private val novelRepo: NovelRepository,
    private val chapterRepo: NovelChapterRepository,
    private val provider: NovelDownloadProvider,
    private val mergedDuplicates: MergedDuplicateRepository,
) : Migration {
    // Fires once when the shipped versionCode crosses 182 (the version this re-key ships in).
    override val version: Float = 182f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val root = storageManager.getNovelDownloadsDirectory() ?: return@withIOContext true
        // The upgrade's dedupe (51.sqm) runs before this and deletes the rows it merges away, recording them
        val novelSurvivors = runCatching { mergedDuplicates.getAll() }.getOrDefault(emptyList())
            .survivorIds(ContentType.NOVELS)
        val chapterSurvivors = runCatching { mergedDuplicates.getChapters() }.getOrDefault(emptyList())
            .survivorIds(ContentType.NOVELS)

        val novelDirs = mutableListOf<UniFile>()
        val moves = root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { dir ->
                // The old scheme names the per-novel dir by its numeric DB id; new-scheme source dirs are
                // plugin ids (and hold title subdirs, not `.html` files), so they yield no matches here.
                val novelId = dir.name?.toLongOrNull() ?: return@flatMap emptyList()
                val novel = lookUp(novelId, novelSurvivors, novelRepo::getById) ?: return@flatMap emptyList()
                novelDirs += dir
                dir.listFiles().orEmpty()
                    .filter { it.isFile && it.name?.endsWith(".html") == true }
                    .mapNotNull { file ->
                        val chapterId = file.name?.removeSuffix(".html")?.toLongOrNull() ?: return@mapNotNull null
                        val chapter = lookUp(chapterId, chapterSurvivors, chapterRepo::getById)
                            ?: return@mapNotNull null
                        Move(file, novel, chapter, merged = novel.id != novelId || chapter.id != chapterId)
                    }
            }
        // A merged-away copy goes last, so a chapter the survivor has keeps the survivor's own download
        moves.sortedBy { it.merged }.forEach { move(it) }
        // Prune the numeric dir once everything moved out.
        novelDirs.forEach { if (it.listFiles().orEmpty().isEmpty()) it.delete() }
        true
    }

    private fun move(move: Move) {
        // Nothing is overwritten, as in the dedupe's download folder merge: both copies of the chapter stay
        if (move.merged && provider.findChapterFile(move.novel, move.chapter) != null) return
        runCatching {
            val html = move.file.openInputStream().bufferedReader().use { it.readText() }
            if (provider.writeChapter(move.novel, move.chapter, html)) move.file.delete()
        }.onFailure {
            logcat(LogPriority.WARN, it) {
                "Novel download re-key failed: novel=${move.novel.id} chapter=${move.chapter.id}"
            }
        }
    }

    /** The row [id] names, or the row it merged into when the dedupe merged it away. */
    private suspend fun <T> lookUp(id: Long, survivors: Map<Long, Long>, get: suspend (Long) -> T?): T? =
        runCatching { get(id) ?: survivors[id]?.let { get(it) } }.getOrNull()

    private class Move(val file: UniFile, val novel: Novel, val chapter: NovelChapter, val merged: Boolean)
}
