package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.download.NovelRemovableDownloads
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadManager

/**
 * Delete the downloads of chapters just marked read, when "delete after marked as read" is on:
 * [SetNovelReadStatus] calls it for a mark made by hand, never for finishing a chapter in the reader.
 * A queued chapter leaves the queue too, as manga's delete dequeues it.
 */
@Inject
class DeleteNovelChaptersAfterRead(
    private val novelPreferences: NovelPreferences,
    private val removableDownloads: NovelRemovableDownloads,
    // Deferred on purpose: building the manager restores the persisted queue and resumes the drain, so
    // taking it directly would resume downloads from every screen that can mark a chapter read. This is
    // the choke point, since the library, details, updates and the notification receiver all reach the
    // manager only through here. See docs/dev/plans/metro-di-migration.md.
    private val downloadManager: () -> NovelDownloadManager,
) {

    /** [chapters] as written, read; filtered here too so a mark that deletes nothing builds no manager. */
    suspend fun await(chapters: List<NovelChapter>) {
        if (chapters.isEmpty() || !novelPreferences.removeAfterMarkedAsRead().get()) return
        val removable = removableDownloads(chapters)
        if (removable.isNotEmpty()) downloadManager().deleteChapters(removable)
    }
}
