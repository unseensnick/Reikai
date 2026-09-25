package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionStoresBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.FeedBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupFeedRow
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMangaMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupMangaSourceRef
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSource
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.yield
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.BufferedSink
import okio.buffer
import okio.gzip
import okio.sink
import reikai.data.backup.backupEntries
import reikai.data.backup.mergeGroupRefs
import reikai.domain.library.ContentType
import reikai.domain.merge.MergeGroupRepository
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Clock

@AssistedInject
class BackupCreator(
    @Assisted private val isAutoBackup: Boolean,
    private val context: Context,
    private val parser: ProtoBuf,
    // RK: getFavorites dropped, since the manga creator reads its own favorites for the shared driver.
    private val backupPreferences: BackupPreferences,
    private val mangaRepository: MangaRepository,
    // RK: source of the persisted manga merge groups, serialized as {url,source} refs.
    private val mergeGroupRepository: MergeGroupRepository,
    private val categoriesBackupCreator: CategoriesBackupCreator,
    private val mangaBackupCreator: MangaBackupCreator,
    private val preferenceBackupCreator: PreferenceBackupCreator,
    private val extensionStoresBackupCreator: ExtensionStoresBackupCreator,
    private val sourcesBackupCreator: SourcesBackupCreator,
    private val backupFileValidator: BackupFileValidator,
    // RK -->
    private val novelBackupCreator: NovelBackupCreator,
    private val extensionBackupCreator: ExtensionBackupCreator,
    private val feedBackupCreator: FeedBackupCreator,
    // RK <--
) {
    @AssistedFactory
    fun interface Factory {
        fun create(isAutoBackup: Boolean): BackupCreator
    }

    // RK: set once any field is written, so a selection that produces no content (e.g. Library
    // entries on but Manga + Novels + everything else off) is rejected instead of writing a useless
    // near-empty file, matching the old empty_backup_error guard the one-shot encode had.
    private var wroteAnything = false

    // RK: the backup is streamed field by field straight to the gzip sink instead of building the
    // whole Backup object graph and encoding it in one ByteArray. The one-shot encode peaked at
    // (object graph + a ~2x transient copy) and OutOfMemoryError'd on large chapter counts
    // (unseensnick/Reikai#53). A protobuf message is just its length-delimited fields concatenated in
    // any order, so per-field streaming is wire-identical: old backups still decode, new ones stay in-format.
    suspend fun backup(uri: Uri, options: BackupOptions): String {
        var file: UniFile? = null
        try {
            file = if (isAutoBackup) {
                // Get dir of file and create
                val dir = UniFile.fromUri(context, uri)

                // Delete older backups
                dir?.listFiles { _, filename -> FILENAME_REGEX.matches(filename) }
                    .orEmpty()
                    .sortedByDescending { it.name }
                    .drop(MAX_AUTO_BACKUPS - 1)
                    .forEach { it.delete() }

                // Create new file to place backup
                dir?.createFile(getFilename())
            } else {
                UniFile.fromUri(context, uri)
            }

            if (file == null || !file.isFile) {
                throw IllegalStateException(context.stringResource(MR.strings.create_backup_file_error))
            }

            // RK -->
            val includeManga = options.libraryEntries && options.includeManga
            val includeNovels = options.libraryEntries && options.includeNovels

            val outputStream = file.openOutputStream()
            // Force overwrite old file
            (outputStream as? FileOutputStream)?.channel?.truncate(0)
            val gzipOut = outputStream.sink().gzip().buffer()

            try {
                val out = gzipOut.outputStream()
                val sourceIds = mutableSetOf<Long>()
                val novelSourceIds = mutableSetOf<String>() // RK

                // Field 1: manga, then field 700 (RK): novels, each streamed through the driver shared
                // by both types, which decides which series are backed up and what each carries.
                if (includeManga) {
                    writeEntries(out, gzipOut, 1, BackupManga.serializer(), options.backupEntries(mangaBackupCreator)) {
                        sourceIds.add(it.source)
                    }
                }
                if (includeNovels) {
                    writeEntries(
                        out,
                        gzipOut,
                        700,
                        BackupNovel.serializer(),
                        options.backupEntries(novelBackupCreator),
                    ) {
                        novelSourceIds.add(it.source)
                    }
                }

                // Remaining fields are small (no per-entry chapter payload), so they are gathered and
                // written after the streamed entries. Field order is irrelevant to the decoder.
                writeEach(out, 2, BackupCategory.serializer(), backupCategories(options))
                writeEach(out, 101, BackupSource.serializer(), sourcesBackupCreator.forSourceIds(sourceIds))
                writeEach(out, 104, BackupPreference.serializer(), backupAppPreferences(options))
                writeEach(out, 105, BackupSourcePreferences.serializer(), backupSourcePreferences(options))
                writeEach(out, 106, BackupExtensionStore.serializer(), backupExtensionStores(options))
                writeEach(out, 710, BackupExtension.serializer(), backupExtensions(options))
                writeEach(out, 717, BackupNovelSource.serializer(), novelBackupCreator.sources(novelSourceIds))
                if (includeManga) {
                    writeEach(out, 711, BackupMangaMergeGroup.serializer(), backupMangaMergeGroups(options))
                }
                // Novel categories ride the Categories option alone, like manga's field 2: a
                // categories-only backup (Library entries off) must still carry both types' rows,
                // or restoring it recreates only half the category list.
                writeEach(out, 701, BackupNovelCategory.serializer(), novelBackupCreator.novelCategories(options))
                if (includeNovels) {
                    writeEach(out, 702, BackupNovelMergeGroup.serializer(), novelBackupCreator.novelMerges(options))
                }
                if (options.savedSearches) {
                    writeEach(out, 715, BackupSavedSearch.serializer(), feedBackupCreator.savedSearches())
                    writeEach(out, 716, BackupFeedRow.serializer(), feedBackupCreator.feedRows())
                }

                gzipOut.flush()
            } finally {
                gzipOut.close()
            }

            if (!wroteAnything) {
                throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
            }
            // RK <--

            val fileUri = file.uri

            // Make sure it's a valid backup file
            backupFileValidator.validate(fileUri)

            if (isAutoBackup) {
                backupPreferences.lastAutoBackupTimestamp.set(Clock.System.now().toEpochMilliseconds())
            }

            return fileUri.toString()
            // RK --> Throwable, not Exception: an OutOfMemoryError is an Error, and catching only Exception
            // left the blank half-written file behind and swallowed the failure (unseensnick/Reikai#53).
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            try {
                file?.delete()
            } catch (deleteError: Exception) {
                logcat(LogPriority.WARN, deleteError) { "Failed to delete partial backup file" }
            }
            // RK <--
            throw e
        }
    }

    // RK -->

    /**
     * Encode and write each streamed entry on its own, so only one is ever held with its chapters, and push
     * the deflated bytes to disk every [MANGA_BATCH_SIZE] entries so nothing accumulates across a big backup.
     */
    private suspend fun <T> writeEntries(
        out: OutputStream,
        gzipOut: BufferedSink,
        fieldNumber: Int,
        serializer: SerializationStrategy<T>,
        entries: Flow<T>,
        onEntry: (T) -> Unit = {},
    ) {
        var written = 0
        entries.collect { entry ->
            onEntry(entry)
            BackupProtoWriter.writeField(out, fieldNumber, parser.encodeToByteArray(serializer, entry))
            wroteAnything = true
            if (++written % MANGA_BATCH_SIZE == 0) {
                gzipOut.flush()
                yield()
            }
        }
        gzipOut.flush()
    }

    /** Encode each item and write it as a repeated length-delimited field. */
    private fun <T> writeEach(
        out: OutputStream,
        fieldNumber: Int,
        serializer: SerializationStrategy<T>,
        items: List<T>,
    ) {
        if (items.isNotEmpty()) wroteAnything = true
        items.forEach { BackupProtoWriter.writeField(out, fieldNumber, parser.encodeToByteArray(serializer, it)) }
    }
    // RK <--

    private suspend fun backupCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        return categoriesBackupCreator()
    }

    private fun backupAppPreferences(options: BackupOptions): List<BackupPreference> {
        if (!options.appSettings) return emptyList()

        return preferenceBackupCreator.createApp(includePrivatePreferences = options.privateSettings)
    }

    // RK --> serialize the persisted manga merge groups as stable {url, source} refs, through the kernel
    // NovelBackupCreator.serializeGroups also calls. Reads the merge_group tables, not the retired prefs;
    // any member resolves by id (not favorites-only). Gated by libraryEntries (merges are meaningless
    // without the library).
    private suspend fun backupMangaMergeGroups(options: BackupOptions): List<BackupMangaMergeGroup> {
        if (!options.libraryEntries) return emptyList()
        return mergeGroupRefs(mergeGroupRepository.getAllMemberships(ContentType.MANGA)) { id ->
            mangaOrNull(id)?.let { BackupMangaSourceRef(url = it.url, source = it.source) }
        }.map { BackupMangaMergeGroup(refs = it) }
    }

    // getMangaById throws on a missing row rather than returning null, so a stale id would abort the
    // whole backup instead of dropping the one entry it belongs to.
    private suspend fun mangaOrNull(id: Long): Manga? = try {
        mangaRepository.getMangaById(id)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    // RK <--

    private suspend fun backupExtensionStores(options: BackupOptions): List<BackupExtensionStore> {
        if (!options.extensionStores) return emptyList()

        return extensionStoresBackupCreator()
    }

    // RK: installed manga and novel extension apps, gated by the same toggle as their repos.
    private suspend fun backupExtensions(options: BackupOptions): List<BackupExtension> {
        if (!options.extensionStores) return emptyList()

        return extensionBackupCreator()
    }

    private suspend fun backupSourcePreferences(options: BackupOptions): List<BackupSourcePreferences> {
        if (!options.sourceSettings) return emptyList()

        return preferenceBackupCreator.createSource(includePrivatePreferences = options.privateSettings)
    }

    companion object {
        private const val MAX_AUTO_BACKUPS: Int = 4

        // RK: how many entries to stream before flushing the gzip buffer to disk, so buffered bytes
        // don't pile up across a very large backup.
        private const val MANGA_BATCH_SIZE: Int = 20

        private val FILENAME_REGEX = """${BuildConfig.APPLICATION_ID}_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}.tachibk""".toRegex()

        fun getFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ENGLISH).format(Date())
            return "${BuildConfig.APPLICATION_ID}_$date.tachibk"
        }
    }
}
