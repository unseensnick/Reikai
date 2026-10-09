package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.BackupProtoReader
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupCustomMangaInfo
import eu.kanade.tachiyomi.data.backup.models.BackupCustomNovelInfo
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupFeedRow
import eu.kanade.tachiyomi.data.backup.models.BackupFields
import eu.kanade.tachiyomi.data.backup.models.BackupMangaMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupMangaSourceRef
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSource
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSourceRef
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LegacyCustomInfo
import eu.kanade.tachiyomi.data.backup.models.novelSourceName
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.FeedRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import reikai.data.backup.mangaTrackScores
import reikai.data.backup.novelTrackScores
import reikai.data.backup.rescaleKitsu
import reikai.data.backup.restoreBatch
import reikai.domain.backup.KitsuBackupScoreScale
import reikai.domain.db.Transactions
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.AdultContentChecker
import reikai.domain.merge.AlignGroupCategories
import reikai.domain.merge.MergeGroupReconstruction
import reikai.domain.merge.PrefEraGrouping
import reikai.domain.merge.ReconcileMergedChapters
import reikai.novel.download.NovelDownloadCache
import reikai.novel.source.NovelSourceManager
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
@AssistedInject
class BackupRestorer(
    @Assisted private val notifier: BackupNotifier,
    @Assisted private val isSync: Boolean,
    private val context: Context,
    private val downloadCache: DownloadCache,
    private val categoriesRestorer: CategoriesRestorer,
    private val preferenceRestorer: PreferenceRestorer,
    private val extensionStoreRestorer: ExtensionStoreRestorer,
    private val mangaRestorer: MangaRestorer,
    private val parser: ProtoBuf, // RK: the streaming restore decodes field by field, no BackupDecoder
    private val sourceManager: SourceManager,
    private val stubSourceRepository: StubSourceRepository,
    // RK -->
    private val novelSourceManager: NovelSourceManager,
    private val novelRestorer: NovelRestorer,
    private val feedRestorer: FeedRestorer,
    private val reconcileMergedChapters: ReconcileMergedChapters,
    private val novelDownloadCache: NovelDownloadCache,
    private val adultContentChecker: AdultContentChecker,
    private val transactions: Transactions,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val alignGroupCategories: AlignGroupCategories,
    // RK <--
) {

    @AssistedFactory
    fun interface Factory {
        fun create(notifier: BackupNotifier, isSync: Boolean): BackupRestorer
    }

    private var restoreAmount = 0
    private val restoreProgress = AtomicInt(0)
    private val errors = CopyOnWriteArrayList<Pair<Date, String>>()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions) {
        val startTime = System.currentTimeMillis()

        // RK: a restored merge group has no stored cross-source stitch yet, and until it does the group
        // badges the leading source's own unread count instead of the group's. Stitching here rather
        // than waiting for the next library update, because a fresh install marks every migration done
        // without running it, so nothing else fills it in. A restore that fails or is cancelled midway
        // has already written groups and chapters, so it is stitched too.
        reconcileMergedChapters.afterPass { restoreFromFile(uri, options) }

        // Invalidate download cache to ensure UI reflects any restored downloads
        if (options.libraryEntries) {
            try {
                downloadCache.invalidateCache()
                novelDownloadCache.invalidate() // RK
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to invalidate download cache after restore" }
            }
        }

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    // RK: restore streams the backup instead of decoding the whole file into memory (which OOMs on a
    // large library, the read side of unseensnick/Reikai#53). Pass 1 (readBackupSummary) gathers the small fields
    // and counts the library entries; the entries themselves are streamed and restored one bounded
    // batch at a time in restoreMangaStream / restoreNovelsStream.
    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        val summary = readBackupSummary(uri)

        // Store source mapping for error messages
        sourceMapping = summary.backupSources.associate { it.sourceId to it.name }

        if (options.libraryEntries) {
            restoreSourceNames(summary.backupSources)
            novelSourceManager.rememberBackedUpNames(summary.novelSourceNames) // RK
            restoreAmount += summary.mangaCount + summary.novelCount
        }
        if (options.categories) {
            restoreAmount += 1
        }
        if (options.appSettings) {
            restoreAmount += 1
        }
        if (options.extensionStores) {
            restoreAmount += summary.backupExtensionStores.size // RK: from the pass-1 summary
        }
        if (options.sourceSettings) {
            restoreAmount += 1
        }

        coroutineScope {
            // RK: categories must finish restoring BEFORE manga + app-settings, both of which map to
            // live categories by name (a manga's category assignments in MangaRestorer.restoreCategories;
            // the default-category pref in PreferenceRestorer). Otherwise an entry restored before its
            // categories exist loses them and lands in Default. Upstream fixes the same race by handing
            // the categories job to those two restorers to await; we await it once here instead, because
            // the novel stream below needs the same wait and would have to be threaded separately.
            if (options.categories) {
                restoreCategories(
                    summary.backupCategories,
                    summary.backupNovelCategories,
                    summary.sortOverridesStored,
                    summary.backupPreferences.takeIf { options.appSettings },
                ).join()
            }
            if (options.appSettings) {
                restoreAppPreferences(
                    summary.backupPreferences,
                    summary.backupCategories.takeIf { options.categories },
                    summary.backupNovelCategories.takeIf { options.categories }, // RK
                )
            }
            if (options.sourceSettings) {
                restoreSourcePreferences(summary.backupSourcePreferences) // RK: from the pass-1 summary
            }
            if (options.libraryEntries) {
                restoreMangaStream(
                    uri,
                    if (options.categories) summary.backupCategories else emptyList(),
                    summary, // RK: the merge groups, older custom info and pref-era grouping inputs
                )
            }
            if (options.extensionStores) {
                restoreExtensionStores(summary.backupExtensionStores) // RK: from the pass-1 summary
            }
            // RK -->
            if (options.savedSearches) {
                restoreIsolated("saved searches") {
                    feedRestorer(summary.backupSavedSearches, summary.backupFeedRows)
                }
            }
            restoreNovelsStream(uri, summary, options)
            // RK <--

            // TODO: optionally trigger online library + tracker update
        }
    }

    // RK: pass 1. Decode only the small fields (kilobytes) and count the streamed library entries, so
    // the restore never has to hold every manga / novel and their chapters in memory at once.
    private suspend fun readBackupSummary(uri: Uri): BackupSummary {
        val backupCategories = mutableListOf<BackupCategory>()
        val backupSources = mutableListOf<BackupSource>()
        val backupPreferences = mutableListOf<BackupPreference>()
        val backupSourcePreferences = mutableListOf<BackupSourcePreferences>()
        val backupExtensionStores = mutableListOf<BackupExtensionStore>()
        val backupMangaMerges = mutableListOf<BackupMangaMergeGroup>()
        val backupCustomMangaInfo = mutableListOf<BackupCustomMangaInfo>()
        val backupNovelCategories = mutableListOf<BackupNovelCategory>()
        val backupNovelMerges = mutableListOf<BackupNovelMergeGroup>()
        val backupCustomNovelInfo = mutableListOf<BackupCustomNovelInfo>()
        val backupSavedSearches = mutableListOf<BackupSavedSearch>()
        val backupFeedRows = mutableListOf<BackupFeedRow>()
        val novelSourceNames = mutableMapOf<String, String>()
        val backupMangaUnmerges = mutableListOf<BackupMangaMergeGroup>()
        val backupNovelUnmerges = mutableListOf<BackupNovelMergeGroup>()
        var mergeGroupsStored = false
        var sortOverridesStored = false
        var kitsuNativeScale = false
        val trackScores = mutableListOf<Pair<Long, Double>>()
        var mangaCount = 0
        var novelCount = 0

        BackupProtoReader(context).read(uri) { fieldNumber, data ->
            when (fieldNumber) {
                BackupFields.MANGA -> {
                    mangaCount++
                    trackScores += parser.mangaTrackScores(data)
                }
                BackupFields.NOVELS -> {
                    novelCount++
                    trackScores += parser.novelTrackScores(data)
                }
                BackupFields.CATEGORIES ->
                    backupCategories.add(parser.decodeFromByteArray(BackupCategory.serializer(), data))
                BackupFields.SOURCES -> backupSources.add(parser.decodeFromByteArray(BackupSource.serializer(), data))
                BackupFields.PREFERENCES ->
                    backupPreferences.add(parser.decodeFromByteArray(BackupPreference.serializer(), data))
                BackupFields.SOURCE_PREFERENCES -> backupSourcePreferences.add(
                    parser.decodeFromByteArray(BackupSourcePreferences.serializer(), data),
                )
                BackupFields.EXTENSION_STORES ->
                    backupExtensionStores.add(parser.decodeFromByteArray(BackupExtensionStore.serializer(), data))
                BackupFields.MANGA_MERGES ->
                    backupMangaMerges.add(parser.decodeFromByteArray(BackupMangaMergeGroup.serializer(), data))
                BackupFields.CUSTOM_MANGA_INFO ->
                    backupCustomMangaInfo.add(parser.decodeFromByteArray(BackupCustomMangaInfo.serializer(), data))
                BackupFields.NOVEL_CATEGORIES ->
                    backupNovelCategories.add(parser.decodeFromByteArray(BackupNovelCategory.serializer(), data))
                BackupFields.NOVEL_MERGES ->
                    backupNovelMerges.add(parser.decodeFromByteArray(BackupNovelMergeGroup.serializer(), data))
                BackupFields.CUSTOM_NOVEL_INFO ->
                    backupCustomNovelInfo.add(parser.decodeFromByteArray(BackupCustomNovelInfo.serializer(), data))
                BackupFields.SAVED_SEARCHES ->
                    backupSavedSearches.add(parser.decodeFromByteArray(BackupSavedSearch.serializer(), data))
                BackupFields.FEED_ROWS ->
                    backupFeedRows.add(parser.decodeFromByteArray(BackupFeedRow.serializer(), data))
                BackupFields.NOVEL_SOURCES -> parser.decodeFromByteArray(BackupNovelSource.serializer(), data).let {
                    novelSourceNames[it.sourceId] = it.name
                }
                BackupFields.MANGA_UNMERGES ->
                    backupMangaUnmerges.add(parser.decodeFromByteArray(BackupMangaMergeGroup.serializer(), data))
                BackupFields.NOVEL_UNMERGES ->
                    backupNovelUnmerges.add(parser.decodeFromByteArray(BackupNovelMergeGroup.serializer(), data))
                BackupFields.MERGE_GROUPS_STORED -> mergeGroupsStored = true
                BackupFields.SORT_OVERRIDES_STORED -> sortOverridesStored = true
                BackupFields.KITSU_NATIVE_SCALE -> kitsuNativeScale = true
            }
        }

        return BackupSummary(
            mangaCount = mangaCount,
            novelCount = novelCount,
            backupCategories = backupCategories,
            backupSources = backupSources,
            backupPreferences = backupPreferences,
            backupSourcePreferences = backupSourcePreferences,
            backupExtensionStores = backupExtensionStores,
            backupMangaMerges = backupMangaMerges,
            legacyCustomInfo = LegacyCustomInfo(backupCustomMangaInfo, backupCustomNovelInfo),
            backupNovelCategories = backupNovelCategories,
            backupNovelMerges = backupNovelMerges,
            backupSavedSearches = backupSavedSearches,
            backupFeedRows = backupFeedRows,
            novelSourceNames = novelSourceNames,
            mergeGroupsStored = mergeGroupsStored,
            sortOverridesStored = sortOverridesStored,
            kitsuScoreScale = KitsuBackupScoreScale.of(kitsuNativeScale, trackScores),
            backupMangaUnmerges = backupMangaUnmerges,
            backupNovelUnmerges = backupNovelUnmerges,
        )
    }

    private data class BackupSummary(
        val mangaCount: Int,
        val novelCount: Int,
        val backupCategories: List<BackupCategory>,
        val backupSources: List<BackupSource>,
        val backupPreferences: List<BackupPreference>,
        val backupSourcePreferences: List<BackupSourcePreferences>,
        val backupExtensionStores: List<BackupExtensionStore>,
        val backupMangaMerges: List<BackupMangaMergeGroup>,
        // An older backup's root custom-info lists, folded onto each entry as it is decoded.
        val legacyCustomInfo: LegacyCustomInfo,
        val backupNovelCategories: List<BackupNovelCategory>,
        val backupNovelMerges: List<BackupNovelMergeGroup>,
        val backupSavedSearches: List<BackupSavedSearch>,
        val backupFeedRows: List<BackupFeedRow>,
        // Absent from a backup made before novels recorded their source names.
        val novelSourceNames: Map<String, String>,
        // False for a 0.3.x backup, whose same-title groups were never stored; the unmerges are its own.
        val mergeGroupsStored: Boolean,
        // False for a backup written before the sort-override bit, whose flags alone hold each category's sort.
        val sortOverridesStored: Boolean,
        // Decided over both types' tracks before either stream restores one.
        val kitsuScoreScale: KitsuBackupScoreScale,
        val backupMangaUnmerges: List<BackupMangaMergeGroup>,
        val backupNovelUnmerges: List<BackupNovelMergeGroup>,
    ) {
        /**
         * The pref-era grouping inputs for [contentType], or null when this backup stores every group.
         * A switch the backup does not carry (no app settings in it) reads as its 0.3.x default.
         */
        fun <R> prefEra(
            contentType: ContentType,
            prefs: ReikaiLibraryPreferences,
            favorites: List<PrefEraGrouping.Favorite<R>>,
            unmerges: List<List<R>>,
        ): PrefEraGrouping<R>? {
            if (mergeGroupsStored) return null
            val switches = MergeGroupReconstruction.titleSwitches(contentType, prefs) { pref ->
                (backupPreferences.find { it.key == pref.key() }?.value as? BooleanPreferenceValue)?.value
                    ?: pref.defaultValue()
            }
            return PrefEraGrouping(favorites, unmerges, switches)
        }
    }

    // RK: restore the light-novel library, streamed: each novel in bounded batches, then the merge
    // groups (re-keyed from {url,source}). Its categories restored with the manga ones, up front.
    private fun CoroutineScope.restoreNovelsStream(
        uri: Uri,
        summary: BackupSummary,
        options: RestoreOptions,
    ) = launch {
        // Mirrors the manga stream's gate: with Categories off, novels must not be assigned to
        // same-named pre-existing categories either. Pinned by RestoreCategoryGateConformanceTest.
        val membershipCategories = if (options.categories) summary.backupNovelCategories else emptyList()
        if (options.libraryEntries) {
            val favorites = mutableListOf<PrefEraGrouping.Favorite<BackupNovelSourceRef>>()
            val kitsuScale = summary.kitsuScoreScale
            restoreEntryStream(
                uri,
                fieldNumber = BackupFields.NOVELS,
                decode = {
                    summary.legacyCustomInfo.decodeNovel(parser, it).rescaleKitsu(kitsuScale).also { novel ->
                        if (!summary.mergeGroupsStored && novel.favorite) {
                            favorites += PrefEraGrouping.Favorite(
                                BackupNovelSourceRef(novel.url, novel.source),
                                novel.title,
                                novel.author,
                            )
                        }
                    }
                },
                restore = { novelRestorer.restore(it, membershipCategories) },
                title = { it.title },
                sourceName = { summary.novelSourceNames.novelSourceName(it.source) },
                isAdult = { adultContentChecker.adultNovelIdsAmong(listOf(it.toNovelImpl())).isNotEmpty() },
            )
            restoreIsolated("novel custom info") {
                summary.legacyCustomInfo.unclaimedNovels().forEach { (ref, info) ->
                    novelRestorer.restoreCustomInfo(ref.first, ref.second, info)
                }
            }

            // Isolated as the manga twin is: a failure here must not cancel the sibling stream or
            // escape before the error log is written.
            restoreIsolated("novel merges") {
                novelRestorer.restoreMerges(
                    summary.backupNovelMerges,
                    summary.prefEra(
                        ContentType.NOVELS,
                        reikaiLibraryPreferences,
                        favorites,
                        summary.backupNovelUnmerges.map { it.refs },
                    ),
                )
                // Entries were filed one at a time from the backup, so a group can come back disagreeing.
                alignGroupCategories.align(ContentType.NOVELS)
            }
        }
    }

    // Without a stub, a source that isn't installed has no name for the next backup to write
    private suspend fun restoreSourceNames(backupSources: List<BackupSource>) {
        backupSources
            .filter { it.name.isNotBlank() }
            .filter {
                sourceManager.get(it.sourceId) == null && stubSourceRepository.getStubSource(it.sourceId) == null
            }
            .forEach { stubSourceRepository.upsertStubSource(it.sourceId, lang = "", name = it.name) }
    }

    private fun CoroutineScope.restoreCategories(
        backupCategories: List<BackupCategory>,
        // RK -->
        // before the app settings, so both content types' category-id settings translate inline
        backupNovelCategories: List<BackupNovelCategory>,
        sortOverridesStored: Boolean,
        restoredPreferences: List<BackupPreference>?,
        // RK <--
    ) = launch {
        ensureActive()
        categoriesRestorer(backupCategories, sortOverridesStored, restoredPreferences) // RK
        // RK -->
        novelRestorer.restoreCategories(backupNovelCategories)
        if (backupCategories.isNotEmpty() || backupNovelCategories.isNotEmpty()) {
            categoriesRestorer.restoreCategorizedDisplay()
        }
        // RK <--

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.categories),
            progress,
            restoreAmount,
            isSync,
        )
    }

    // RK: pass 2 for manga. Streams field 1, restoring bounded batches inside a DB transaction (each
    // restore also opens its own, harmlessly nested), then materializes the merge groups once every
    // manga has a fresh id. Upstream's sortByNew order is dropped: it restored series the device lacks
    // first, so a large restore cut short still added them, and keeping it would take a second pass
    // over a file this reads once, in file order.
    private fun CoroutineScope.restoreMangaStream(
        uri: Uri,
        backupCategories: List<BackupCategory>,
        summary: BackupSummary,
    ) = launch {
        val legacyCustomInfo = summary.legacyCustomInfo
        val favorites = mutableListOf<PrefEraGrouping.Favorite<BackupMangaSourceRef>>()
        restoreEntryStream(
            uri,
            fieldNumber = BackupFields.MANGA,
            decode = {
                legacyCustomInfo.decodeManga(parser, it).rescaleKitsu(summary.kitsuScoreScale).also { manga ->
                    if (!summary.mergeGroupsStored && manga.favorite) {
                        favorites += PrefEraGrouping.Favorite(
                            BackupMangaSourceRef(manga.url, manga.source),
                            manga.title,
                            manga.author,
                        )
                    }
                }
            },
            restore = { mangaRestorer.restore(listOf(it), backupCategories) },
            title = { it.title },
            sourceName = { sourceMapping[it.source] ?: it.source.toString() },
            isAdult = { adultContentChecker.adultIdsAmong(listOf(it.getMangaImpl())).isNotEmpty() },
        )
        restoreIsolated("manga custom info") {
            legacyCustomInfo.unclaimedManga().forEach { (ref, info) ->
                mangaRestorer.restoreCustomInfo(ref.first, ref.second, info)
            }
        }

        // RK: with every manga restored (fresh IDs), materialize the backup's merge groups. Isolated
        // like the entry loop above: a failure here would otherwise cancel the novel stream mid-batch
        // and escape before the error log is written, leaving a half-restored library and no report.
        ensureActive()
        restoreIsolated("merges") {
            mangaRestorer.restoreMerges(
                summary.backupMangaMerges,
                summary.prefEra(
                    ContentType.MANGA,
                    reikaiLibraryPreferences,
                    favorites,
                    summary.backupMangaUnmerges.map { it.refs },
                ),
            )
            // RK: entries were filed one at a time from the backup, so a group can come back disagreeing
            alignGroupCategories.align(ContentType.MANGA)
        }
    }

    // RK: the streamed restore loop both content types run: [fieldNumber]'s entries are decoded one at a
    // time and restored in bounded batches through restoreBatch, which keeps a bad entry to itself.
    private suspend fun <B> restoreEntryStream(
        uri: Uri,
        fieldNumber: Int,
        decode: (ByteArray) -> B,
        restore: suspend (B) -> Unit,
        title: (B) -> String,
        sourceName: (B) -> String,
        isAdult: suspend (B) -> Boolean,
    ) {
        val batch = ArrayList<B>(RESTORE_CHUNK)
        suspend fun flush() {
            if (batch.isEmpty()) return
            restoreBatch(batch, transactions, restore).forEach { (entry, e) ->
                errors.add(Date() to "${title(entry)} [${sourceName(entry)}]: ${e.message}")
            }
            restoreProgress.addAndFetch(batch.size)

            val last = batch.last()
            notifier.showRestoreProgress(
                title(last),
                restoreProgress.load(),
                restoreAmount,
                isSync,
                isAdult = isAdult(last),
            )
            batch.clear()
        }

        BackupProtoReader(context).read(uri) { field, data ->
            if (field != fieldNumber) return@read
            currentCoroutineContext().ensureActive()
            batch.add(decode(data))
            if (batch.size >= RESTORE_CHUNK) flush()
        }
        flush()
    }

    // RK: run a post-loop restore phase, recording a failure instead of taking the whole restore down.
    private suspend fun restoreIsolated(phase: String, block: suspend () -> Unit) {
        runCatchingCancellable { block() }.onFailure { errors.add(Date() to "$phase: ${it.message}") }
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
        novelCategories: List<BackupNovelCategory>?, // RK
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
            novelCategories, // RK
        )

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.app_settings),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreSourcePreferences(preferences: List<BackupSourcePreferences>) = launch {
        ensureActive()
        preferenceRestorer.restoreSource(preferences)

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.source_settings),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreExtensionStores(
        backupExtensionStores: List<BackupExtensionStore>,
    ) = launch {
        backupExtensionStores
            .chunked(RESTORE_CHUNK) // RK
            .forEach { chunk ->
                chunk.forEach {
                    ensureActive()

                    try {
                        extensionStoreRestorer(it)
                    } catch (e: Exception) {
                        errors.add(Date() to "Error Adding Repo: ${it.name} : ${e.message}")
                    }

                    restoreProgress.incrementAndFetch()
                }
                notifier.showRestoreProgress(
                    context.stringResource(MR.strings.extensionStores),
                    restoreProgress.load(),
                    restoreAmount,
                    isSync,
                )
            }
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("reikai_restore_error.txt") // RK: Reikai-branded dump name
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (_: Exception) {
            // Empty
        }
        return File("")
    }

    // RK -->
    companion object {
        // RK: entries per DB transaction while streaming; also the memory bound (only this many
        // entries + their chapters are resident at once).
        private const val RESTORE_CHUNK = 100
    }
    // RK <--
}
