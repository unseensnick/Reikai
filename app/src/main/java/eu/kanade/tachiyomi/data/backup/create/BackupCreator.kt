package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionStoresBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupCustomMangaInfo
import eu.kanade.tachiyomi.data.backup.models.BackupCustomNovelInfo
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMangaMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupMangaSourceRef
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import kotlinx.coroutines.yield
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import reikai.domain.library.ContentType
import reikai.domain.merge.MergeGroupRepository
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

class BackupCreator(
    private val context: Context,
    private val isAutoBackup: Boolean,

    private val parser: ProtoBuf = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private val backupPreferences: BackupPreferences = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    // RK: source of the persisted manga merge groups, serialized as {url,source} refs.
    private val mergeGroupRepository: MergeGroupRepository = Injekt.get(),
    // RK: source of the manga custom-info overlay, backed up as {url,source}-keyed entries.
    private val customMangaInfoRepository: CustomMangaInfoRepository = Injekt.get(),

    private val categoriesBackupCreator: CategoriesBackupCreator = CategoriesBackupCreator(),
    private val mangaBackupCreator: MangaBackupCreator = MangaBackupCreator(),
    private val preferenceBackupCreator: PreferenceBackupCreator = PreferenceBackupCreator(),
    private val extensionStoresBackupCreator: ExtensionStoresBackupCreator = ExtensionStoresBackupCreator(),
    private val sourcesBackupCreator: SourcesBackupCreator = SourcesBackupCreator(),
    // RK -->
    private val novelBackupCreator: NovelBackupCreator = NovelBackupCreator(),
    private val extensionBackupCreator: ExtensionBackupCreator = ExtensionBackupCreator(),
    // RK <--
) {

    // RK: set once any field is written, so a selection that produces no content (e.g. Library
    // entries on but Manga + Novels + everything else off) is rejected instead of writing a useless
    // near-empty file, matching the old empty_backup_error guard the one-shot encode had.
    private var wroteAnything = false

    // RK: the backup is streamed field by field straight to the gzip sink instead of building the
    // whole Backup object graph and encoding it in one ByteArray. The one-shot encode peaked at
    // (object graph + a ~2x transient copy) and OutOfMemoryError'd on large chapter counts
    // (Issue #53). A protobuf message is just its length-delimited fields concatenated in any order,
    // so per-field streaming is wire-identical: old backups still decode, new ones stay in-format.
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

            // Favorites carry no chapters, so the metadata list is light even for a big library; the
            // chapters (the OOM driver) are only ever resident one batch at a time via the stream.
            val includeManga = options.libraryEntries && options.includeManga
            val includeNovels = options.libraryEntries && options.includeNovels
            val favorites = if (includeManga) getFavorites.await() else emptyList()

            val outputStream = file.openOutputStream()
            // Force overwrite old file
            (outputStream as? FileOutputStream)?.channel?.truncate(0)
            val gzipOut = outputStream.sink().gzip().buffer()

            try {
                val out = gzipOut.outputStream()
                val sourceIds = mutableSetOf<Long>()

                // Field 1: manga, streamed. Each is encoded and written on its own, then collected.
                if (includeManga) {
                    val nonFavorite = if (options.readEntries) {
                        mangaRepository.getReadMangaNotInLibrary()
                    } else {
                        emptyList()
                    }
                    (favorites + nonFavorite).chunked(MANGA_BATCH_SIZE).forEach { batch ->
                        mangaBackupCreator.backupMangaStream(batch, options).collect { manga ->
                            sourceIds.add(manga.source)
                            BackupProtoWriter.writeField(
                                out,
                                1,
                                parser.encodeToByteArray(BackupManga.serializer(), manga),
                            )
                            wroteAnything = true
                        }
                        // Push the batch's deflated bytes to disk so nothing accumulates across a big backup.
                        gzipOut.flush()
                        yield()
                    }
                }

                // Field 700 (RK): novels, streamed the same way.
                if (includeNovels) {
                    var written = 0
                    novelBackupCreator.streamNovels(options).collect { novel ->
                        BackupProtoWriter.writeField(
                            out,
                            700,
                            parser.encodeToByteArray(BackupNovel.serializer(), novel),
                        )
                        wroteAnything = true
                        if (++written % MANGA_BATCH_SIZE == 0) {
                            gzipOut.flush()
                            yield()
                        }
                    }
                    gzipOut.flush()
                }

                // Remaining fields are small (no per-entry chapter payload), so they are gathered and
                // written after the streamed entries. Field order is irrelevant to the decoder.
                writeEach(out, 2, BackupCategory.serializer(), backupCategories(options))
                writeEach(out, 101, BackupSource.serializer(), sourcesBackupCreator.forSourceIds(sourceIds))
                writeEach(out, 104, BackupPreference.serializer(), backupAppPreferences(options))
                writeEach(out, 105, BackupSourcePreferences.serializer(), backupSourcePreferences(options))
                writeEach(out, 106, BackupExtensionStore.serializer(), backupExtensionStores(options))
                // RK -->
                writeEach(out, 710, BackupExtension.serializer(), backupExtensions(options))
                if (includeManga) {
                    writeEach(out, 711, BackupMangaMergeGroup.serializer(), backupMangaMergeGroups(options))
                    if (options.customInfo) {
                        writeEach(
                            out,
                            713,
                            BackupCustomMangaInfo.serializer(),
                            backupCustomMangaInfo(favorites, options),
                        )
                    }
                }
                if (includeNovels) {
                    writeEach(out, 701, BackupNovelCategory.serializer(), novelBackupCreator.novelCategories(options))
                    writeEach(out, 702, BackupNovelMergeGroup.serializer(), novelBackupCreator.novelMerges(options))
                    if (options.customInfo) {
                        writeEach(out, 714, BackupCustomNovelInfo.serializer(), novelBackupCreator.novelCustomInfo())
                    }
                }
                // RK <--

                gzipOut.flush()
            } finally {
                gzipOut.close()
            }

            if (!wroteAnything) {
                throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
            }

            val fileUri = file.uri

            // Make sure it's a valid backup file (streamed, so it doesn't re-inflate the whole file).
            BackupFileValidator(context).validate(fileUri)

            if (isAutoBackup) {
                backupPreferences.lastAutoBackupTimestamp.set(Instant.now().toEpochMilli())
            }

            return fileUri.toString()
        } catch (e: Throwable) {
            // Throwable, not Exception: an OutOfMemoryError is an Error, and catching only Exception
            // left the blank half-written file behind and swallowed the failure (Issue #53).
            logcat(LogPriority.ERROR, e)
            try {
                file?.delete()
            } catch (deleteError: Exception) {
                logcat(LogPriority.WARN, deleteError) { "Failed to delete partial backup file" }
            }
            throw e
        }
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

    private suspend fun backupCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        return categoriesBackupCreator()
    }

    private fun backupAppPreferences(options: BackupOptions): List<BackupPreference> {
        if (!options.appSettings) return emptyList()

        return preferenceBackupCreator.createApp(includePrivatePreferences = options.privateSettings)
    }

    // RK: serialize the persisted manga merge groups as stable {url, source} refs (the manga twin of
    // NovelBackupCreator.serializeGroups). Reads the merge_group tables, not the retired prefs; any
    // member resolves by id (not favorites-only). Gated by libraryEntries (merges are meaningless without
    // the library). A group is dropped if fewer than two members resolve.
    private suspend fun backupMangaMergeGroups(options: BackupOptions): List<BackupMangaMergeGroup> {
        if (!options.libraryEntries) return emptyList()
        val memberships = mergeGroupRepository.getAllMemberships(ContentType.MANGA)
        if (memberships.isEmpty()) return emptyList()
        return memberships.entries
            .groupBy({ it.value }, { it.key })
            .values
            .mapNotNull { memberIds ->
                val refs = memberIds.map { id ->
                    val manga = mangaRepository.getMangaById(id)
                    BackupMangaSourceRef(url = manga.url, source = manga.source)
                }
                refs.takeIf { it.size >= 2 }?.let { BackupMangaMergeGroup(refs = it) }
            }
    }

    // RK: back up the manga custom-info overlay as {url, source}-keyed entries (re-keyed to fresh ids on
    // restore). Favorites-only, so the favorites map resolves each row's manga; a row for a non-favorite
    // (shouldn't happen) is dropped. Gated by libraryEntries.
    private suspend fun backupCustomMangaInfo(
        favorites: List<Manga>,
        options: BackupOptions,
    ): List<BackupCustomMangaInfo> {
        if (!options.libraryEntries) return emptyList()
        val byId = favorites.associateBy { it.id }
        return customMangaInfoRepository.getAll().mapNotNull { info ->
            val manga = byId[info.mangaId] ?: return@mapNotNull null
            BackupCustomMangaInfo(
                source = manga.source,
                url = manga.url,
                title = info.title,
                author = info.author,
                artist = info.artist,
                description = info.description,
                genre = info.genre.orEmpty(),
                status = info.status,
                thumbnailUrl = info.thumbnailUrl,
            )
        }
    }

    private suspend fun backupExtensionStores(options: BackupOptions): List<BackupExtensionStore> {
        if (!options.extensionStores) return emptyList()

        return extensionStoresBackupCreator()
    }

    // RK: installed manga extensions, gated by the same toggle as their repos.
    private fun backupExtensions(options: BackupOptions): List<BackupExtension> {
        if (!options.extensionStores) return emptyList()

        return extensionBackupCreator()
    }

    private fun backupSourcePreferences(options: BackupOptions): List<BackupSourcePreferences> {
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
