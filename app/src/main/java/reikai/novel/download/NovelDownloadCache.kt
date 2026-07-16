package reikai.novel.download

import eu.kanade.tachiyomi.data.download.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory index of which novel chapters are downloaded, derived from a disk scan rather than a DB
 * flag, mirroring the manga [eu.kanade.tachiyomi.data.download.DownloadCache]. Disk is the source of
 * truth, so downloaded state survives reinstall / restore / storage-move and can't drift out of sync.
 *
 * The tree is `source dir -> novel dir -> chapter file names present on disk`. Queries answer from the
 * current (possibly stale) tree synchronously and kick a background [renew] when it is past
 * [RENEW_INTERVAL_MS]; the renew is a full scan and emits [changes], so the UI re-queries with fresh
 * data (eventually consistent, exactly like the manga cache). Files ending in the downloader's
 * [Downloader.TMP_DIR_SUFFIX] are skipped so a half-written chapter isn't counted. There is no proto
 * snapshot (a scan is always the fallback); startup does a full scan.
 *
 * Keyed on strings internally; the typed [isChapterDownloaded] / [getDownloadCount] shims derive the
 * folder + file names through [NovelDownloadProvider] (which reuses the manga naming), so a future
 * unified download layer can lift this string core across both content types.
 */
class NovelDownloadCache {

    private val storageManager: StorageManager by injectLazy()
    private val provider: NovelDownloadProvider by injectLazy()

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _changes = Channel<Unit>(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .onStart { emit(Unit) }
        .shareIn(scope, SharingStarted.Lazily, 1)

    /** source dir name -> (novel dir name -> chapter file names on disk). Swapped whole (copy-on-write). */
    @Volatile
    private var tree: Map<String, Map<String, Set<String>>> = emptyMap()

    @Volatile
    private var lastRenew = 0L

    private val renewing = AtomicBoolean(false)

    /** Serializes tree edits so a mutator and a renew can't clobber each other's read-modify-write. */
    private val mutex = Mutex()

    init {
        // Re-scan when the storage location moves: the files relocate under the new root.
        storageManager.changes.onEach { invalidate() }.launchIn(scope)
        scope.launch { renew() }
    }

    // String overloads are the real query API (callers with denormalized rows avoid rebuilding a Novel);
    // the typed overloads are thin shims.

    fun isChapterDownloaded(source: String, title: String, chapterName: String, chapterUrl: String): Boolean {
        renewIfStale()
        val names = tree[provider.sourceDirName(source)]?.get(provider.novelDirName(title)) ?: return false
        return provider.validChapterFileNames(chapterName, chapterUrl).any { it in names }
    }

    fun getDownloadCount(source: String, title: String): Int {
        renewIfStale()
        return tree[provider.sourceDirName(source)]?.get(provider.novelDirName(title))?.size ?: 0
    }

    fun isChapterDownloaded(novel: Novel, chapter: NovelChapter): Boolean =
        isChapterDownloaded(novel.source, novel.title, chapter.name, chapter.url)

    /**
     * Ids of the [chapters] that are downloaded, all assumed to belong to [novel]. Resolves the novel's
     * source/title folder once (only the per-chapter file-name check remains), so a long chapter list
     * doesn't recompute the constant folder names per chapter as a `isChapterDownloaded`-per-chapter loop
     * would. Result is identical to filtering with [isChapterDownloaded].
     */
    fun downloadedChapterIds(novel: Novel, chapters: List<NovelChapter>): Set<Long> {
        if (chapters.isEmpty()) return emptySet()
        renewIfStale()
        val names = tree[provider.sourceDirName(novel.source)]?.get(provider.novelDirName(novel.title))
            ?: return emptySet()
        return chapters
            .filter { ch -> provider.validChapterFileNames(ch.name, ch.url).any { it in names } }
            .mapTo(HashSet()) { it.id }
    }

    fun getDownloadCount(novel: Novel): Int = getDownloadCount(novel.source, novel.title)

    /** Optimistically record a just-written chapter so the UI reflects it without waiting for a scan. */
    fun addChapter(novel: Novel, chapter: NovelChapter) {
        val source = provider.sourceDirName(novel)
        val novelDir = provider.novelDirName(novel)
        val file = provider.chapterFileName(chapter)
        scope.launch {
            mutex.withLock {
                tree = tree.mutate { sources ->
                    sources.getOrPut(source) { mutableMapOf() }
                        .getOrPut(novelDir) { mutableSetOf() }
                        .add(file)
                }
            }
            notifyChanges()
        }
    }

    /** Optimistically drop a just-deleted chapter, pruning now-empty novel / source dirs. */
    fun removeChapter(novel: Novel, chapter: NovelChapter) {
        val source = provider.sourceDirName(novel)
        val novelDir = provider.novelDirName(novel)
        val names = provider.validChapterFileNames(chapter).toSet()
        scope.launch {
            mutex.withLock {
                tree = tree.mutate { sources ->
                    val novels = sources[source] ?: return@mutate
                    novels[novelDir]?.removeAll(names)
                    if (novels[novelDir]?.isEmpty() == true) novels.remove(novelDir)
                    if (novels.isEmpty()) sources.remove(source)
                }
            }
            notifyChanges()
        }
    }

    /** Drop a whole novel's downloads from the index (its dir was deleted). */
    fun removeNovel(novel: Novel) {
        val source = provider.sourceDirName(novel)
        val novelDir = provider.novelDirName(novel)
        scope.launch {
            mutex.withLock {
                tree = tree.mutate { sources ->
                    sources[source]?.remove(novelDir)
                    if (sources[source]?.isEmpty() == true) sources.remove(source)
                }
            }
            notifyChanges()
        }
    }

    /** Follow a chapter's on-disk rename in the index: drop the old file name, add the new one. */
    fun renameChapter(novel: Novel, oldChapter: NovelChapter, newChapter: NovelChapter) {
        val source = provider.sourceDirName(novel)
        val novelDir = provider.novelDirName(novel)
        val oldNames = provider.validChapterFileNames(oldChapter).toSet()
        val newName = provider.chapterFileName(newChapter)
        scope.launch {
            mutex.withLock {
                tree = tree.mutate { sources ->
                    val files = sources[source]?.get(novelDir) ?: return@mutate
                    files.removeAll(oldNames)
                    files.add(newName)
                }
            }
            notifyChanges()
        }
    }

    private fun renewIfStale() {
        if (System.currentTimeMillis() - lastRenew > RENEW_INTERVAL_MS && !renewing.get()) {
            scope.launch { renew() }
        }
    }

    private fun invalidate() {
        lastRenew = 0L
        scope.launch { renew() }
    }

    /** Full disk scan: rebuild the tree from what is actually on disk. */
    private suspend fun renew() {
        if (!renewing.compareAndSet(false, true)) return
        try {
            val root = storageManager.getNovelDownloadsDirectory()
            val scanned: Map<String, Map<String, Set<String>>> = buildMap {
                root?.listFiles().orEmpty()
                    .filter { it.isDirectory && !it.name.isNullOrBlank() }
                    .forEach { sourceDir ->
                        val novels = buildMap<String, Set<String>> {
                            sourceDir.listFiles().orEmpty()
                                .filter { it.isDirectory && !it.name.isNullOrBlank() }
                                .forEach { novelDir ->
                                    val files = novelDir.listFiles().orEmpty()
                                        .mapNotNull { it.name }
                                        .filterNot { it.endsWith(Downloader.TMP_DIR_SUFFIX) }
                                        .toSet()
                                    if (files.isNotEmpty()) put(novelDir.name!!, files)
                                }
                        }
                        if (novels.isNotEmpty()) put(sourceDir.name!!, novels)
                    }
            }
            mutex.withLock {
                tree = scanned
                lastRenew = System.currentTimeMillis()
            }
            notifyChanges()
        } finally {
            renewing.set(false)
        }
    }

    private fun notifyChanges() {
        scope.launch { _changes.send(Unit) }
    }

    private inline fun Map<String, Map<String, Set<String>>>.mutate(
        block: (MutableMap<String, MutableMap<String, MutableSet<String>>>) -> Unit,
    ): Map<String, Map<String, Set<String>>> {
        val copy = mapValuesTo(mutableMapOf()) { (_, novels) ->
            novels.mapValuesTo(mutableMapOf()) { (_, files) -> files.toMutableSet() }
        }
        block(copy)
        return copy
    }

    companion object {
        /** Re-scan disk at most this often for a stale query (out-of-band changes; in-app writes update
         *  the tree immediately). Matches the manga cache's hourly cadence. */
        private const val RENEW_INTERVAL_MS = 60 * 60 * 1000L
    }
}
