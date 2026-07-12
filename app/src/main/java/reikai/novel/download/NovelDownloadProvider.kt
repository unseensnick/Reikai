package reikai.novel.download

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.injectLazy

/**
 * Resolves on-disk locations for downloaded novel chapter text. Stable-name scheme mirroring the manga
 * [DownloadProvider]: `<novel downloads dir>/<source>/<novel title>/<chapter name>_<url hash>.html`.
 * Because the path is derived from stable metadata (not the row's numeric DB ids), a downloaded chapter
 * survives reinstall / restore / storage-move, and the folders are human-readable (the old
 * `<novelId>/<chapterId>.html` scheme broke on any id reshuffle and was opaque).
 *
 * The `<source>` segment keys on the **plugin id** ([Novel.source]) rather than a display name: a novel
 * source's display name can change on a plugin update, so the id is the hardier folder key (the manga
 * side keys on display name because its numeric source ids are stable). Title + chapter naming is
 * **reused** from the manga [DownloadProvider] (not copied) so the two schemes can never drift, which
 * a later unified download layer depends on; a parity test pins this.
 *
 * Text-only: one self-contained HTML file per chapter (inline images embedded as `data:` URIs). Writes
 * land on a `<name>.html<TMP_DIR_SUFFIX>` temp file and rename on success, so a [NovelDownloadCache]
 * scan mid-download never counts a half-written file (mirrors the manga Downloader's tmp-then-rename).
 */
class NovelDownloadProvider {

    private val storageManager: StorageManager by injectLazy()
    private val downloadProvider: DownloadProvider by injectLazy()
    private val libraryPreferences: LibraryPreferences by injectLazy()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var downloadsDir = storageManager.getNovelDownloadsDirectory()

    init {
        storageManager.changes.onEach {
            downloadsDir = storageManager.getNovelDownloadsDirectory()
        }.launchIn(scope)
    }

    // Name derivation. The string overloads are the real API (so callers with denormalized rows, e.g.
    // the Updates screen, don't need to rebuild a Novel); the typed overloads are thin shims. Source is
    // novel-specific (plugin id); title + chapter reuse the manga provider so both content types produce
    // byte-identical folder/file names for the same inputs.

    fun sourceDirName(source: String): String =
        DiskUtil.buildValidFilename(
            source,
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get(),
        )

    fun novelDirName(title: String): String = downloadProvider.getMangaDirName(title)

    /** The on-disk file name a chapter's download is written to: `<manga-style chapter dir name>.html`. */
    fun chapterFileName(chapterName: String, chapterUrl: String): String =
        downloadProvider.getChapterDirName(chapterName, null, chapterUrl) + HTML_EXT

    /**
     * File names a chapter's download might sit under: the current one plus the variant produced under
     * the opposite non-ASCII-filenames setting, so toggling that setting doesn't orphan a download
     * (mirrors the manga [DownloadProvider.getValidChapterDirNames]). No `.cbz`: novels are text-only.
     */
    fun validChapterFileNames(chapterName: String, chapterUrl: String): List<String> {
        val ascii = libraryPreferences.disallowNonAsciiFilenames.get()
        return listOf(
            downloadProvider.getChapterDirName(chapterName, null, chapterUrl, ascii) + HTML_EXT,
            downloadProvider.getChapterDirName(chapterName, null, chapterUrl, !ascii) + HTML_EXT,
        ).distinct()
    }

    fun sourceDirName(novel: Novel): String = sourceDirName(novel.source)
    fun novelDirName(novel: Novel): String = novelDirName(novel.title)
    fun chapterFileName(chapter: NovelChapter): String = chapterFileName(chapter.name, chapter.url)
    fun validChapterFileNames(chapter: NovelChapter): List<String> =
        validChapterFileNames(chapter.name, chapter.url)

    private fun novelDir(novel: Novel): UniFile? =
        downloadsDir?.createDirectory(sourceDirName(novel))?.createDirectory(novelDirName(novel))

    private fun findNovelDir(novel: Novel): UniFile? =
        downloadsDir?.findFile(sourceDirName(novel))?.findFile(novelDirName(novel))

    private fun findChapterFile(novel: Novel, chapter: NovelChapter): UniFile? {
        val dir = findNovelDir(novel) ?: return null
        return validChapterFileNames(chapter).firstNotNullOfOrNull { dir.findFile(it) }
    }

    fun isChapterDownloaded(novel: Novel, chapter: NovelChapter): Boolean =
        findChapterFile(novel, chapter)?.exists() == true

    fun readChapter(novel: Novel, chapter: NovelChapter): String? =
        findChapterFile(novel, chapter)?.takeIf { it.exists() }
            ?.openInputStream()?.bufferedReader()?.use { it.readText() }

    /**
     * Persist a chapter's HTML, creating parent dirs. Writes to a temp file then renames, so a scan
     * never sees a partial file. False when no storage dir is configured or the rename fails.
     */
    fun writeChapter(novel: Novel, chapter: NovelChapter, html: String): Boolean {
        val dir = novelDir(novel) ?: return false
        val finalName = chapterFileName(chapter)
        val tmpName = finalName + Downloader.TMP_DIR_SUFFIX
        dir.findFile(tmpName)?.delete()
        val tmp = dir.createFile(tmpName) ?: return false
        tmp.openOutputStream().bufferedWriter().use { it.write(html) }
        // Drop any prior final file so a re-download's rename doesn't fail on an existing target.
        dir.findFile(finalName)?.delete()
        return tmp.renameTo(finalName)
    }

    fun deleteChapter(novel: Novel, chapter: NovelChapter) {
        findChapterFile(novel, chapter)?.delete()
    }

    fun deleteNovel(novel: Novel) {
        findNovelDir(novel)?.delete()
    }

    /**
     * Rename a downloaded chapter's file when its title changes, so the stable-name path follows the new
     * title (mirrors the manga [eu.kanade.tachiyomi.data.download.DownloadManager] rename-on-sync).
     * No-op when nothing is downloaded or the name is unchanged; returns true only when a file was
     * actually renamed.
     */
    fun renameChapter(novel: Novel, oldChapter: NovelChapter, newChapter: NovelChapter): Boolean {
        val dir = findNovelDir(novel) ?: return false
        val oldFile = validChapterFileNames(oldChapter).firstNotNullOfOrNull { dir.findFile(it) } ?: return false
        val newName = chapterFileName(newChapter)
        if (oldFile.name == newName) return false
        return oldFile.renameTo(newName)
    }

    companion object {
        private const val HTML_EXT = ".html"
    }
}
