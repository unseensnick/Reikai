package tachiyomi.domain.storage.service

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import mihon.core.metro.AppCoroutineScope
import java.util.concurrent.ConcurrentHashMap

@Inject
@SingleIn(AppScope::class)
class StorageManager(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    storagePreferences: StoragePreferences,
) {

    @Volatile
    private var baseDirLazy: Lazy<UniFile?> = lazy { getBaseDir(storagePreferences.baseStorageDirectory.get()) }
    private val baseDir: UniFile?
        get() = baseDirLazy.value

    // On SAF, createDirectory lists the whole base directory to find an existing child, so a resolved child
    // is reused while it's under the current base directory and still exists; it can be deleted outside the app
    private val childDirectories = ConcurrentHashMap<String, Pair<UniFile, UniFile>>()

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .shareIn(scope, SharingStarted.Lazily, 1)

    init {
        storagePreferences.baseStorageDirectory.changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                baseDirLazy = lazyOf(getBaseDir(uri))
                baseDir?.let { parent ->
                    parent.createDirectory(AUTOMATIC_BACKUPS_PATH)
                    parent.createDirectory(LOCAL_SOURCE_PATH)
                    parent.createDirectory(DOWNLOADS_PATH).also {
                        DiskUtil.createNoMediaFile(it, context)
                    }
                    // RK --> light-novel chapter downloads, separate from manga downloads, the
                    // reader fonts the user imports or downloads, and the local novels
                    parent.createDirectory(NOVEL_DOWNLOADS_PATH).also {
                        DiskUtil.createNoMediaFile(it, context)
                    }
                    parent.createDirectory(FONTS_PATH)
                    parent.createDirectory(LOCAL_NOVELS_PATH)
                    // RK <--
                }
                _changes.send(Unit)
            }
            .launchIn(scope)
    }

    private fun getBaseDir(uri: String): UniFile? {
        return UniFile.fromUri(context, uri.toUri())
            .takeIf { it?.exists() == true }
    }

    fun getAutomaticBackupsDirectory(): UniFile? {
        return getChildDirectory(AUTOMATIC_BACKUPS_PATH)
    }

    fun getDownloadsDirectory(): UniFile? {
        return getChildDirectory(DOWNLOADS_PATH)
    }

    fun getLocalSourceDirectory(): UniFile? {
        return getChildDirectory(LOCAL_SOURCE_PATH)
    }

    private fun getChildDirectory(name: String): UniFile? {
        val parent = baseDir ?: return null
        childDirectories[name]
            ?.takeIf { (cachedParent, directory) -> cachedParent === parent && directory.exists() }
            ?.let { (_, directory) -> return directory }
        return parent.createDirectory(name)?.also { childDirectories[name] = parent to it }
    }

    // RK --> light-novel chapter downloads
    fun getNovelDownloadsDirectory(): UniFile? {
        return getChildDirectory(NOVEL_DOWNLOADS_PATH)
    }

    /** Reader fonts the user imported or downloaded. Deliberately not marked `.nomedia`: these are the
     *  user's own files and a file manager should show them. */
    fun getFontsDirectory(): UniFile? {
        return getChildDirectory(FONTS_PATH)
    }

    /** The local novel source's books, a folder per series, beside the manga local source's `local`. */
    fun getLocalNovelsDirectory(): UniFile? {
        return getChildDirectory(LOCAL_NOVELS_PATH)
    }
    // RK <--
}

private const val AUTOMATIC_BACKUPS_PATH = "autobackup"
private const val DOWNLOADS_PATH = "downloads"
private const val LOCAL_SOURCE_PATH = "local"

// RK: light-novel chapter downloads, the reader fonts the user adds and the local novels
private const val NOVEL_DOWNLOADS_PATH = "novel_downloads"
private const val FONTS_PATH = "fonts"
private const val LOCAL_NOVELS_PATH = "localnovels"
