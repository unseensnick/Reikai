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
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupCustomMangaInfo
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMangaMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupMangaSourceRef
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileOutputStream
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
    // RK: source of the manga merge/unmerge prefs serialized as {url,source} refs.
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences = Injekt.get(),
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

            val favorites = getFavorites.await()
            val nonFavoriteManga = if (options.readEntries) mangaRepository.getReadMangaNotInLibrary() else emptyList()
            val backupManga = backupMangas(favorites + nonFavoriteManga, options)

            // RK: the light-novel library (favorites + chapters/categories/tracks/history + merges).
            val novelData = novelBackupCreator(options)

            val backup = Backup(
                backupManga = backupManga,
                backupCategories = backupCategories(options),
                backupSources = backupSources(backupManga),
                backupPreferences = backupAppPreferences(options),
                backupExtensionStores = backupExtensionStores(options),
                backupSourcePreferences = backupSourcePreferences(options),
                // RK -->
                backupNovels = novelData.novels,
                backupNovelCategories = novelData.categories,
                backupNovelMerges = novelData.merges,
                backupNovelUnmerges = novelData.unmerges,
                backupExtensions = backupExtensions(options),
                backupMangaMerges = backupMangaMergeGroups(reikaiLibraryPreferences.mangaManualMerges.get(), favorites, options),
                backupMangaUnmerges = backupMangaMergeGroups(reikaiLibraryPreferences.mangaManualUnmerges.get(), favorites, options),
                backupCustomMangaInfo = backupCustomMangaInfo(favorites, options),
                // RK <--
            )

            val byteArray = parser.encodeToByteArray(Backup.serializer(), backup)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
            }

            file.openOutputStream()
                .also {
                    // Force overwrite old file
                    (it as? FileOutputStream)?.channel?.truncate(0)
                }
                .sink().gzip().buffer().use {
                    it.write(byteArray)
                }
            val fileUri = file.uri

            // Make sure it's a valid backup file
            BackupFileValidator(context).validate(fileUri)

            if (isAutoBackup) {
                backupPreferences.lastAutoBackupTimestamp.set(Instant.now().toEpochMilli())
            }

            return fileUri.toString()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            file?.delete()
            throw e
        }
    }

    private suspend fun backupCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        return categoriesBackupCreator()
    }

    private suspend fun backupMangas(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        if (!options.libraryEntries) return emptyList()

        return mangaBackupCreator(mangas, options)
    }

    private fun backupSources(mangas: List<BackupManga>): List<BackupSource> {
        return sourcesBackupCreator(mangas)
    }

    private fun backupAppPreferences(options: BackupOptions): List<BackupPreference> {
        if (!options.appSettings) return emptyList()

        return preferenceBackupCreator.createApp(includePrivatePreferences = options.privateSettings)
    }

    // RK: translate a merge-pref's comma-joined ID groups into stable {url, source} refs (the manga twin
    // of NovelBackupCreator.serializeGroups). Gated by libraryEntries (merges are meaningless without the
    // library). A group is dropped if fewer than two members resolve, since a one-member group is no
    // longer a merge. Merge members are favorites, so the favorites map resolves them all.
    private fun backupMangaMergeGroups(
        groups: Set<String>,
        favorites: List<Manga>,
        options: BackupOptions,
    ): List<BackupMangaMergeGroup> {
        if (!options.libraryEntries || groups.isEmpty()) return emptyList()
        val byId = favorites.associateBy { it.id }
        return groups.mapNotNull { group ->
            val refs = group.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .mapNotNull { id -> byId[id]?.let { BackupMangaSourceRef(url = it.url, source = it.source) } }
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
        private val FILENAME_REGEX = """${BuildConfig.APPLICATION_ID}_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}.tachibk""".toRegex()

        fun getFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ENGLISH).format(Date())
            return "${BuildConfig.APPLICATION_ID}_$date.tachibk"
        }
    }
}
