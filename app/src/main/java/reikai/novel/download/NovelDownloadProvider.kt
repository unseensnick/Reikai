package reikai.novel.download

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import reikai.data.dedupe.DownloadFolderMerge
import reikai.domain.download.chapterFileLookup
import reikai.domain.download.movesDownloadFolder
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.source.isNovelAppSourceId
import reikai.storage.deleteFile
import reikai.storage.deleteIfEmpty
import reikai.storage.deleteTree
import reikai.storage.listOrNull
import reikai.storage.rename
import reikai.storage.replace
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager

/**
 * Resolves on-disk locations for downloaded novel chapter text, mirroring the manga [DownloadProvider]:
 * `<novel downloads dir>/<source>/<novel title>/<chapter name>[_<url hash>].html`, the chapter part named by
 * manga's rule, hash setting included. Derived from stable metadata rather than numeric DB ids, so a download
 * survives reinstall, restore and storage moves. The `<source>` segment is [sourceFolderLabels], renamed with its
 * source ([sourceFolders]). One self-contained HTML file per chapter, written to a temp name and renamed on success.
 */
@Inject
@SingleIn(AppScope::class)
class NovelDownloadProvider(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val storageManager: StorageManager,
    private val downloadProvider: DownloadProvider,
    private val libraryPreferences: LibraryPreferences,
    novelPreferences: NovelPreferences,
) {

    private var downloadsDir = storageManager.getNovelDownloadsDirectory()

    // The seen record names every source loaded, installed or since removed. Read once per change rather than
    // per lookup, since the download cache names a source for every library row.
    @Volatile
    private var folderLabels = novelPreferences.seenNovelSources().get().let(::sourceFolderLabels)

    /**
     * Each source's folder label, emitted only once a source whose label changed has had its folder renamed,
     * so the download cache can rescan on it and find every download where it now is.
     */
    val sourceFolders: StateFlow<Map<String, String>> = novelPreferences.seenNovelSources().changes()
        .map(::sourceFolderLabels)
        .onEach { labels ->
            val renamed = labels.keys.filter { folderLabels[it] != labels[it] }
            renameSourceFolders(renamed, from = { folderLabels[it] ?: it }, to = { labels.getValue(it) })
            folderLabels = labels
        }
        .stateIn(scope, SharingStarted.Eagerly, folderLabels)

    init {
        storageManager.changes.onEach {
            downloadsDir = storageManager.getNovelDownloadsDirectory()
        }.launchIn(scope)
    }

    // Name derivation. The string overloads are the real API (so callers with denormalized rows, e.g.
    // the Updates screen, don't need to rebuild a Novel); the typed overloads are thin shims. Source is
    // novel-specific (plugin id); title + chapter reuse the manga provider so both content types produce
    // byte-identical folder/file names for the same inputs.

    fun sourceDirName(source: String): String = folderName(sourceFolderLabel(source))

    /** What [source]'s folder is called before it is made a valid file name: see [sourceFolderLabels]. */
    fun sourceFolderLabel(source: String): String = folderLabels[source] ?: source

    /**
     * Moves each of [sources]' folders from the label [from] gives it to the one [to] gives it, where the old one
     * exists and the new one is free; a taken name leaves the folder where it is, its downloads unread but kept.
     */
    fun renameSourceFolders(sources: Collection<String>, from: (String) -> String, to: (String) -> String) {
        val root = downloadsDir ?: return
        sources.forEach { source ->
            val target = folderName(to(source))
            val folder = root.findFile(folderName(from(source)))?.takeIf { it.isDirectory } ?: return@forEach
            // No other entry is named onto a source folder, so only a taken name keeps it in place
            if (folder.name == target || !movesDownloadFolder(folder, target, emptyList())) return@forEach
            folder.rename(target)
        }
    }

    /**
     * Merges each of [sources]' folders at [from]'s label into the folder at [to]'s, where [renameSourceFolders] left
     * it because that name was taken: two catalogues of one name and language share one folder, as manga's do. A
     * title both hold gets the chapters it lacks, and a chapter both hold stays in both.
     */
    fun mergeSourceFolders(sources: Collection<String>, from: (String) -> String, to: (String) -> String) {
        val root = downloadsDir ?: return
        sources.forEach { source ->
            val target = folderName(to(source))
            val name = folderName(from(source))
            if (name.equals(target, ignoreCase = true)) return@forEach
            val folder = root.findFile(name)?.takeIf { it.isDirectory } ?: return@forEach
            val into = root.findFile(target)?.takeIf { it.isDirectory } ?: return@forEach
            DownloadFolderMerge.merge(context, from = folder, into = into)
            folder.listFiles().orEmpty().filter { it.isDirectory }.forEach { title ->
                into.findFile(title.name ?: return@forEach)?.takeIf { it.isDirectory }
                    ?.let { DownloadFolderMerge.merge(context, from = title, into = it) }
            }
            folder.deleteIfEmpty(context)
        }
    }

    private fun folderName(label: String): String =
        DiskUtil.buildValidFilename(label, disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get())

    fun novelDirName(title: String): String = downloadProvider.getMangaDirName(title)

    /** The on-disk file name a chapter's download is written to: `<manga-style chapter dir name>.html`. */
    fun chapterFileName(
        chapterName: String,
        chapterUrl: String,
        enableChapterNameHash: Boolean = libraryPreferences.enableChapterNameHash.get(),
    ): String = downloadProvider.getChapterDirName(
        chapterName,
        null,
        chapterUrl,
        enableChapterNameHash = enableChapterNameHash,
    ) + HTML_EXT

    /**
     * File names a chapter's download might sit under, current first: the manga provider's names for it under
     * every file-name setting, so changing one doesn't orphan a download. No `.cbz`: novels are text-only.
     */
    fun validChapterFileNames(chapterName: String, chapterUrl: String): List<String> =
        downloadProvider.getChapterNameVariants(chapterName, null, chapterUrl).map { it + HTML_EXT }

    fun sourceDirName(novel: Novel): String = sourceDirName(novel.source)
    fun novelDirName(novel: Novel): String = novelDirName(novel.title)
    fun chapterFileName(chapter: NovelChapter): String = chapterFileName(chapter.name, chapter.url)
    fun validChapterFileNames(chapter: NovelChapter): List<String> =
        validChapterFileNames(chapter.name, chapter.url)

    private fun novelDir(novel: Novel): UniFile? =
        downloadsDir?.createDirectory(sourceDirName(novel))?.createDirectory(novelDirName(novel))

    /** The novel's download directory, or null when nothing has been downloaded yet. Public for the
     *  details overflow's Open folder, through [NovelDownloadManager.findNovelDir]. */
    fun findNovelDir(novel: Novel): UniFile? =
        downloadsDir?.findFile(sourceDirName(novel))?.findFile(novelDirName(novel))

    fun findChapterFile(novel: Novel, chapter: NovelChapter): UniFile? {
        val dir = findNovelDir(novel) ?: return null
        return validChapterFileNames(chapter).firstNotNullOfOrNull { dir.findFile(it) }
    }

    fun readChapter(novel: Novel, chapter: NovelChapter): String? =
        findChapterFile(novel, chapter)?.takeIf { it.exists() }
            ?.openInputStream()?.bufferedReader()?.use { it.readText() }

    /**
     * Persist a chapter's HTML, creating parent dirs, through [replace]: written beside the earlier file and
     * swapped in once whole, so a scan never sees a partial file and a failed write or rename keeps the earlier
     * one. False when no storage dir is configured or the file could not take its name.
     */
    fun writeChapter(
        novel: Novel,
        chapter: NovelChapter,
        html: String,
        finalName: String = chapterFileName(chapter),
    ): Boolean {
        val dir = novelDir(novel) ?: return false
        return runCatchingCancellable {
            dir.replace(context, finalName) { it.bufferedWriter().use { writer -> writer.write(html) } }
        }
            .onFailure { logcat(LogPriority.WARN, it) { "Could not write ${novel.title} / $finalName" } }
            .isSuccess
    }

    /** Deletes [chapters]' files, all [novel]'s, each the first of its names found, as Mihon's findChapterDirs picks. */
    fun deleteChapters(novel: Novel, chapters: List<NovelChapter>) {
        val find = findNovelDir(novel)?.chapterFileLookup(chapters.size) ?: return
        chapters.forEach { chapter -> validChapterFileNames(chapter).firstNotNullOfOrNull(find)?.deleteFile() }
    }

    /** Deletes the novel's folder, then its source's folder once a trusted listing shows it empty, as manga's does. */
    fun deleteNovel(novel: Novel) {
        findNovelDir(novel)?.deleteTree()
        downloadsDir?.findFile(sourceDirName(novel))?.deleteIfEmpty(context)
    }

    /**
     * Moves the novel's folder to [newTitle]'s name by [rename]. False when there was nothing to move or the move
     * failed.
     */
    fun renameNovel(novel: Novel, newTitle: String): Boolean {
        val dir = findNovelDir(novel) ?: return false
        val newName = novelDirName(newTitle)
        if (dir.name == newName) return false
        return dir.rename(newName)
    }

    /** Free bytes on the volume novel downloads go to, or -1 when it cannot be read. */
    fun availableSpace(): Long = downloadsDir?.let(DiskUtil::getAvailableStorageSpace) ?: -1L

    /** Whether nothing is left in the novel's folder, by a trusted listing, since a failed one reads as empty too. */
    fun isNovelDirEmpty(novel: Novel): Boolean = findNovelDir(novel)?.listOrNull(context)?.isEmpty() == true

    /**
     * Rename a downloaded chapter's file when its title changes, so the stable-name path follows the new
     * title (mirrors the manga [eu.kanade.tachiyomi.data.download.DownloadManager] rename-on-sync, pinned by
     * RenameOnSyncConformanceTest, and named by the manga provider's getChapterDirName on both sides).
     * No-op when nothing is downloaded or the name is unchanged; returns true only when a file was
     * actually renamed.
     */
    fun renameChapter(novel: Novel, oldChapter: NovelChapter, newChapter: NovelChapter): Boolean {
        val dir = findNovelDir(novel) ?: return false
        val oldFile = validChapterFileNames(oldChapter).firstNotNullOfOrNull { dir.findFile(it) } ?: return false
        val newName = chapterFileName(newChapter)
        if (oldFile.name == newName) return false
        return oldFile.rename(newName)
    }

    companion object {
        private const val HTML_EXT = ".html"
    }
}

/**
 * The folder label of every source the seen record names: an app catalogue as manga's `Source.toString()` names
 * one, `Name (LANG)`; a plugin by the id its updates keep, with its name beside it to tell the folders apart.
 */
internal fun sourceFolderLabels(seen: Map<String, LnSourceIdentity>): Map<String, String> =
    seen.mapValues { (id, identity) ->
        val name = identity.name.takeIf { it.isNotBlank() && it != id }
        when {
            isNovelAppSourceId(id) && name != null ->
                identity.lang?.takeIf { it.isNotBlank() }?.let { "$name (${it.uppercase()})" } ?: name
            name != null -> "$id ($name)"
            else -> id
        }
    }
