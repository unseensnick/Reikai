package reikai.novel.download

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
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
import mihon.core.metro.AppCoroutineScope
import reikai.domain.download.DownloadIndexRules
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.storage.recoverInFlightRenames
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val storageManager: StorageManager,
    private val provider: NovelDownloadProvider,
    private val downloadPreferences: DownloadPreferences,
) {

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

    // Bumped by invalidate, so a scan that read the disk before it knows to scan again.
    private val invalidations = AtomicInteger(0)

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
        // A source renamed has had its folder renamed by the time this emits, so a rescan finds its downloads.
        provider.sourceFolders.drop(1).onEach { invalidate() }.launchIn(scope)
        scope.launch {
            restoreIndex()
            renewIfStale()
        }
    }

    /** Throw the index away and rescan, for a restore or the Settings action that asks for it. */
    fun invalidate() {
        invalidations.incrementAndGet()
        lastRenew = 0L
        // Deleted on the scope, as the manga cache does, so a caller on the main thread never touches the disk
        scope.launch {
            indexFile.delete()
            renew()
        }
    }

    fun isChapterDownloaded(source: String, title: String, chapterName: String, chapterUrl: String): Boolean {
        renewIfStale()
        val names = tree[provider.sourceDirName(source)]?.get(provider.novelDirName(title)) ?: return false
        return provider.validChapterFileNames(chapterName, chapterUrl).any { it in names }
    }

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

    fun getDownloadCount(novel: Novel): Int {
        renewIfStale()
        return tree[provider.sourceDirName(novel.source)]?.get(provider.novelDirName(novel.title))?.size ?: 0
    }

    /** Optimistically record a just-written chapter so the UI reflects it without waiting for a scan. */
    fun addChapter(novel: Novel, chapter: NovelChapter) {
        val source = provider.sourceDirName(novel)
        val novelDir = provider.novelDirName(novel)
        val file = provider.chapterFileName(chapter)
        scope.launch {
            mutex.withLock {
                tree = tree.mutateSource(source) { novels -> novels.editNovel(novelDir) { it.add(file) } }
            }
            notifyChanges()
        }
    }

    /**
     * Optimistically drop just-deleted [chapters], all [novel]'s, pruning now-empty novel / source dirs. One
     * edit and one change for the batch, as Mihon's DownloadCache.removeChapters, since each change rebuilds
     * the novel's page and the library.
     */
    fun removeChapters(novel: Novel, chapters: List<NovelChapter>) {
        val source = provider.sourceDirName(novel)
        val novelDir = provider.novelDirName(novel)
        val names = chapters.flatMapTo(HashSet()) { provider.validChapterFileNames(it) }
        scope.launch {
            mutex.withLock {
                tree = tree.mutateSource(source) { novels -> novels.editNovel(novelDir) { it.removeAll(names) } }
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
                tree = tree.mutateSource(source) { novels -> novels.remove(novelDir) }
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
                tree = tree.mutateSource(source) { novels ->
                    if (novelDir !in novels) return@mutateSource
                    novels.editNovel(novelDir) { files ->
                        files.removeAll(oldNames)
                        files.add(newName)
                    }
                }
            }
            notifyChanges()
        }
    }

    /** Follow a novel's folder rename in the index. */
    fun renameNovel(novel: Novel, newTitle: String) {
        val source = provider.sourceDirName(novel)
        val oldDir = provider.novelDirName(novel)
        val newDir = provider.novelDirName(newTitle)
        scope.launch {
            mutex.withLock {
                tree = tree.mutateSource(source) { novels -> novels.remove(oldDir)?.let { novels[newDir] = it } }
            }
            notifyChanges()
        }
    }
    private fun renewIfStale() {
        val renewHours = downloadPreferences.downloadIndexRenewHours.get()
        if (DownloadIndexRules.isStale(lastRenew, System.currentTimeMillis(), renewHours) && !renewing.get()) {
            scope.launch { renew() }
        }
    }

    /** Full disk scan: rebuild the tree from what is actually on disk. */
    private suspend fun renew() {
        // A scan already running rescans on its own if an invalidate lands meanwhile.
        if (!renewing.compareAndSet(false, true)) return
        val scannedAt = invalidations.get()
        try {
            if (lastRenew == 0L) _isInitializing.value = true
            // Held across the scan, as Mihon's renewCache holds its lock: an edit made mid-scan waits and
            // lands on the new tree, where the swap would otherwise overwrite it with the older listing.
            val current = mutex.withLock {
                val scanned = scan(storageManager.getNovelDownloadsDirectory())
                // Scanned before an invalidate: the root it read may be the old one, so keep nothing.
                if (invalidations.get() != scannedAt) return@withLock false
                tree = scanned
                lastRenew = System.currentTimeMillis()
                true
            }
            if (current) notifyChanges()
        } finally {
            _isInitializing.value = false
            renewing.set(false)
        }
        // An invalidate that arrived during the scan, or just after it, found the flag held and gave up.
        if (invalidations.get() != scannedAt) scope.launch { renew() }
    }

    private fun scan(root: UniFile?): Map<String, Map<String, Set<String>>> = buildMap {
        // A source or novel folder a killed case-only rename left in flight is put back before it is read.
        recoverInFlightRenames(root?.listFiles().orEmpty().toList())
            .filter { it.isDirectory && !it.name.isNullOrBlank() }
            .forEach { sourceDir ->
                val novels = buildMap<String, Set<String>> {
                    recoverInFlightRenames(sourceDir.listFiles().orEmpty().toList())
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
        _changes.trySend(Unit)
        saveIndex()
    }

    /**
     * Written a second after the last change, so a burst of downloads writes once. Synchronized, since changes
     * arrive from several IO threads and two unguarded swaps would leave a job nothing cancels.
     */
    @Synchronized
    private fun saveIndex() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1.seconds)
            val snapshot = SavedIndex(tree, lastRenew)
            try {
                // Moved over the old file once whole, as Mihon's AtomicFile writes its index, so a kill mid-write
                // keeps the last good copy
                val part = File(indexFile.path + ".part").apply { writeText(json.encodeToString(snapshot)) }
                Files.move(part.toPath(), indexFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to write the novel download index" }
            }
        }
    }

    /**
     * The tree with [source]'s novels edited by [block]. Only that source's map is copied, and only the sets
     * [editNovel] touches: the tree is never edited in place, so everything else can be shared. A source left
     * empty is dropped.
     */
    private inline fun Map<String, Map<String, Set<String>>>.mutateSource(
        source: String,
        block: (MutableMap<String, Set<String>>) -> Unit,
    ): Map<String, Map<String, Set<String>>> {
        val novels = this[source].orEmpty().toMutableMap()
        block(novels)
        return toMutableMap().apply { if (novels.isEmpty()) remove(source) else put(source, novels) }
    }

    /** [dir]'s file names edited by [block] on a copy, so a set the tree shares is never changed. Dropped once empty. */
    private inline fun MutableMap<String, Set<String>>.editNovel(dir: String, block: (MutableSet<String>) -> Unit) {
        val files = this[dir].orEmpty().toMutableSet().also(block)
        if (files.isEmpty()) remove(dir) else put(dir, files)
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
