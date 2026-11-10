package reikai.data.local

import dev.zacsweers.metro.Inject
import reikai.domain.library.ContentType
import reikai.novel.local.LocalNovelSource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.io.LocalSourceFileSystem

/**
 * Whether the file a local chapter row names is still in the folder, for both local sources' address
 * shapes: a manga chapter is series/file or series/volume/file, with a volume's Extras the volume folder
 * itself (a trailing slash); a novel chapter is inside its volume's book, so its file is the book.
 */
@Inject
class LocalChapterFiles(
    private val fileSystem: LocalSourceFileSystem,
    private val storageManager: StorageManager,
) {

    /** The address prefix of the container [url] is read out of, or null for a chapter that is its own file. */
    fun containerOf(type: ContentType, url: String): String? = when (type) {
        ContentType.NOVELS -> LocalNovelSource.parseChapterPath(url)
            ?.let { (series, volume) -> LocalNovelSource.volumePathPrefix(series, volume) }
        else -> url.takeIf { it.endsWith('/') }
    }

    suspend fun exists(type: ContentType, url: String): Boolean = withIOContext {
        when (type) {
            ContentType.NOVELS -> {
                val (series, volume) = LocalNovelSource.parseChapterPath(url) ?: return@withIOContext false
                if (!LocalNovelSource.isChildName(series) || !LocalNovelSource.isChildName(volume)) {
                    return@withIOContext false
                }
                storageManager.getLocalNovelsDirectory()?.findFile(series)?.findFile(volume)?.exists() == true
            }
            else -> url.split('/').filter { it.isNotEmpty() }
                .fold(fileSystem.getBaseDirectory()) { dir, part -> dir?.findFile(part) }
                ?.exists() == true
        }
    }
}
