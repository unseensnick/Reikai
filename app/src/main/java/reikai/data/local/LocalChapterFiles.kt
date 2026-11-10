package reikai.data.local

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import reikai.domain.library.ContentType
import reikai.novel.local.LocalNovelSource
import reikai.storage.FolderUnreadableException
import reikai.storage.list
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.io.LocalSourceFileSystem

/**
 * Whether the file a local chapter row names is still in its folder, for both local sources' address shapes: a
 * manga chapter is series/file or series/volume/file, with a volume's Extras the volume folder itself (a trailing
 * slash); a novel chapter is inside its volume's book, so its file is the book, or the series itself for a loose
 * book. Answered from a listing of the folder trusted whole, never from the file's own query, which a provider
 * hiccup answers "absent"; a listing that cannot be trusted fails the refresh instead.
 */
@Inject
class LocalChapterFiles(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
    private val storageManager: StorageManager,
) {

    /** The address prefix of the container [url] is read out of, or null for a chapter that is its own file. */
    fun containerOf(type: ContentType, url: String): String? = when (type) {
        ContentType.NOVELS -> LocalNovelSource.parseChapterPath(url)
            ?.let { (series, volume) -> LocalNovelSource.volumePathPrefix(series, volume) }
        else -> url.takeIf { it.endsWith('/') }
    }

    /** One refresh's listings, each folder read once however many removed chapters sit in it. */
    fun listings(): Listings = Listings()

    inner class Listings {
        private val entries = HashMap<Pair<ContentType, List<String>>, Map<String, UniFile>?>()

        /** Whether the file the chapter at [url] is read from is in its folder, by that folder's trusted listing. */
        suspend fun present(type: ContentType, url: String): Boolean = withIOContext {
            val (parent, name) = place(type, url) ?: return@withIOContext false
            entriesOf(type, parent)?.containsKey(name) == true
        }

        /** The folder at [path] under the type's root as name to file, or null when a listing above shows it absent. */
        private fun entriesOf(type: ContentType, path: List<String>): Map<String, UniFile>? {
            val key = type to path
            // Not getOrPut, which re-runs its body for a stored null, and an absent folder is stored as null.
            if (key in entries) return entries[key]
            val folder = if (path.isEmpty()) {
                root(type) ?: throw FolderUnreadableException("No local folder")
            } else {
                entriesOf(type, path.dropLast(1))?.get(path.last())
            }
            return folder?.list(context)?.associateBy { it.name.orEmpty() }.also { entries[key] = it }
        }
    }

    private fun root(type: ContentType): UniFile? = when (type) {
        ContentType.NOVELS -> storageManager.getLocalNovelsDirectory()
        else -> fileSystem.getBaseDirectory()
    }

    /** The folder path and the name of the file [url] is read from, or null for an address no local file has. */
    private fun place(type: ContentType, url: String): Pair<List<String>, String>? = when (type) {
        ContentType.NOVELS -> {
            val (series, volume) = LocalNovelSource.parseChapterPath(url) ?: return null
            if (!LocalNovelSource.isChildName(series) || !LocalNovelSource.isChildName(volume)) return null
            if (volume == series) emptyList<String>() to series else listOf(series) to volume
        }
        else -> {
            val parts = url.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            parts.dropLast(1) to parts.last()
        }
    }
}
