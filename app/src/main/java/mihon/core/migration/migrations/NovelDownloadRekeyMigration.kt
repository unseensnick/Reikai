package mihon.core.migration.migrations

import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
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
 */
class NovelDownloadRekeyMigration : Migration {
    // RK: fires once when the shipped versionCode crosses 182 (the version this re-key ships in).
    override val version: Float = 182f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        if (migrationContext.previousVersion == 0) return@withIOContext true // fresh install: nothing to move
        val storageManager = migrationContext.get<StorageManager>() ?: return@withIOContext false
        val novelRepo = migrationContext.get<NovelRepository>() ?: return@withIOContext false
        val chapterRepo = migrationContext.get<NovelChapterRepository>() ?: return@withIOContext false
        val provider = migrationContext.get<NovelDownloadProvider>() ?: return@withIOContext false

        val root = storageManager.getNovelDownloadsDirectory() ?: return@withIOContext true

        root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .forEach { dir ->
                // The old scheme names the per-novel dir by its numeric DB id; new-scheme source dirs are
                // plugin ids (and hold title subdirs, not `.html` files), so they yield no matches here.
                val novelId = dir.name?.toLongOrNull() ?: return@forEach
                val novel = runCatching { novelRepo.getById(novelId) }.getOrNull() ?: return@forEach
                dir.listFiles().orEmpty()
                    .filter { it.isFile && it.name?.endsWith(".html") == true }
                    .forEach file@{ oldFile ->
                        val chapterId = oldFile.name?.removeSuffix(".html")?.toLongOrNull() ?: return@file
                        val chapter = runCatching { chapterRepo.getById(chapterId) }.getOrNull() ?: return@file
                        runCatching {
                            val html = oldFile.openInputStream().bufferedReader().use { it.readText() }
                            if (provider.writeChapter(novel, chapter, html)) oldFile.delete()
                        }.onFailure {
                            logcat(LogPriority.WARN, it) {
                                "Novel download re-key failed: novel=$novelId chapter=$chapterId"
                            }
                        }
                    }
                // Prune the numeric dir once everything moved out.
                if (dir.listFiles().orEmpty().isEmpty()) dir.delete()
            }
        true
    }
}
