package reikai.novel.download

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import reikai.domain.download.DownloadIndexRules
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Index of which novel chapters are downloaded, derived from a disk scan rather than a DB flag, mirroring the manga
 * [eu.kanade.tachiyomi.data.download.DownloadCache]: disk is the source of truth, and the index is saved between
 * launches so a start does not rescan. Queries answer from the possibly-stale tree synchronously and kick a background
 * [renew] once [DownloadIndexRules.isStale], which rescans and emits [changes]: eventually consistent, like the manga
 * cache.
 */
@Inject
@SingleIn(AppScope::class)
class NovelDownloadCache(
    private val context: Context,
    private val storageManager: StorageManager,
    private val provider: NovelDownloadProvider,
) {

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

    private val _isInitializing = MutableStateFlow(false)

    /** True while a first scan builds an index from nothing, as the manga cache reports it. */
    val isInitializing: StateFlow<Boolean> = _isInitializing
        .debounce(1.seconds) // Don't notify if it finishes quickly enough
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val indexFile: File get() = File(context.cacheDir, INDEX_FILE)

    private var saveJob: Job? = null

    init {
        // Re-scan when the storage location moves: the files relocate under the new root.
        storageManager.changes.onEach { invalidate() }.launchIn(scope)
        scope.launch {
            restoreIndex()
            renewIfStale()
        }
    }

    /** Throw the index away and rescan, for a restore or the Settings action that asks for it. */
    fun invalidate() {
        lastRenew = 0L
        indexFile.delete()
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
        if (DownloadIndexRules.isStale(lastRenew, System.currentTimeMillis()) && !renewing.get()) {
            scope.launch { renew() }
        }
    }

    /** Full disk scan: rebuild the tree from what is actually on disk. */
    private suspend fun renew() {
        if (!renewing.compareAndSet(false, true)) return
        try {
            if (lastRenew == 0L) _isInitializing.value = true
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
                                        .filter(DownloadIndexRules::isIndexed)
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
            _isInitializing.value = false
            renewing.set(false)
        }
    }

    private suspend fun restoreIndex() {
        val saved = try {
            indexFile.takeIf { it.exists() }?.readText()?.let { json.decodeFromString<SavedIndex>(it) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read the novel download index" }
            indexFile.delete()
            null
        } ?: return
        mutex.withLock {
            // A write that landed first is newer than anything saved.
            if (lastRenew == 0L && tree.isEmpty()) {
                tree = saved.sources
                lastRenew = saved.lastRenew
            }
        }
        _changes.send(Unit)
    }

    private fun notifyChanges() {
        scope.launch { _changes.send(Unit) }
        saveIndex()
    }

    /** Written a second after the last change, so a burst of downloads writes once. */
    private fun saveIndex() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1.seconds)
            val snapshot = SavedIndex(tree, lastRenew)
            try {
                indexFile.writeText(json.encodeToString(snapshot))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to write the novel download index" }
            }
        }
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

    @Serializable
    private data class SavedIndex(
        val sources: Map<String, Map<String, Set<String>>> = emptyMap(),
        val lastRenew: Long = 0L,
    )

    private companion object {
        const val INDEX_FILE = "novel_dl_index_v1.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}
