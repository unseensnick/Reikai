package exh.debug

import android.app.job.JobScheduler
import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.util.system.workManager
import exh.eh.EHentaiUpdateWorker
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.source.EH_SOURCE_ID
import exh.source.EXH_SOURCE_ID
import exh.source.EnhancedHttpSource
import exh.util.ThrottleManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.schema.ProtoBufSchemaGenerator
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.core.migration.MigrationJobFactory
import mihon.core.migration.MigrationStrategyFactory
import mihon.core.migration.Migrator
import mihon.domain.source.interactor.UpdateMangaFromRemote
import reikai.data.novel.update.NovelUpdateJob
import reikai.domain.debug.DebugDatabaseRepository
import reikai.presentation.browse.MangaLibraryAdder
import tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetSearchMetadata
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.util.UUID

/**
 * Komikku's debug functions, which the debug menu lists by reflection: every public function here is
 * a row, and what it returns is shown. Re-typed to Reikai; the two Komikku has with no counterpart
 * here, and why, are in docs/dev/plans/exh-subsystem.md. `proguard-rules.pro` keeps the public members.
 */
@Suppress("unused")
@Inject
class DebugFunctions(
    private val context: Context,
    private val migrations: Set<Migration>,
    private val sourceManager: SourceManager,
    private val getEnabledSources: GetEnabledSources,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val getFavorites: GetFavorites,
    private val getFlatMetadataById: GetFlatMetadataById,
    private val insertFlatMetadata: InsertFlatMetadata,
    private val getExhFavoriteMangaWithMetadata: GetExhFavoriteMangaWithMetadata,
    private val getSearchMetadata: GetSearchMetadata,
    private val mangaRepository: MangaRepository,
    private val debugDatabase: DebugDatabaseRepository,
    private val mangaLibraryAdder: MangaLibraryAdder,
) {

    suspend fun forceUpgradeMigration(): Boolean = runMigrations(fromVersion = 1)

    suspend fun forceSetupJobs(): Boolean = runMigrations(fromVersion = 0)

    suspend fun resetAgedFlagInExhManga() {
        getExhFavoriteMangaWithMetadata.await().forEach { manga ->
            val meta = getFlatMetadataById.await(manga.id)?.raise<EHentaiSearchMetadata>() ?: return@forEach
            meta.aged = false
            insertFlatMetadata.await(meta)
        }
    }

    suspend fun getDelegatedSourceList(): String = sourceManager.getAll()
        .filterIsInstance<EnhancedHttpSource>()
        .joinToString(separator = "\n") {
            it.name + " : " + it.id + " : " + it.enhancedSource::class.qualifiedName
        }

    suspend fun resetEHGalleriesForUpdater() {
        val throttleManager = ThrottleManager()
        getExhFavoriteMangaWithMetadata.await().forEach { manga ->
            throttleManager.throttle()
            updateMangaFromRemote(manga, fetchDetails = true, manualFetch = true)
        }
    }

    suspend fun getEHMangaListWithAgedFlagInfo(): String {
        val result = getExhFavoriteMangaWithMetadata.await().mapNotNull { manga ->
            val meta = getFlatMetadataById.await(manga.id)?.raise<EHentaiSearchMetadata>()
            meta?.let { "Aged: ${meta.aged}\t-\tTitle: ${manga.title}" }
        }
        return (listOf("Count: ${result.size}") + result).joinToString(",\n")
    }

    suspend fun countAgedFlagInExhManga(): Int = getExhFavoriteMangaWithMetadata.await().count { manga ->
        getFlatMetadataById.await(manga.id)?.raise<EHentaiSearchMetadata>()?.aged == true
    }

    // Through the add sequence every add takes, rather than Komikku's bare favorite write.
    suspend fun addAllMangaInDatabaseToLibrary() {
        debugDatabase.getAllManga()
            .filterNot { it.favorite }
            .forEach { mangaLibraryAdder.addWithoutAsking(it.id) }
    }

    suspend fun countMangaInDatabaseInLibrary(): Int = getFavorites.await().size

    suspend fun countMangaInDatabaseNotInLibrary(): Int = debugDatabase.getAllManga().count { !it.favorite }

    suspend fun countMangaInDatabase(): Int = debugDatabase.getAllManga().size

    suspend fun countMetadataInDatabase(): Int = getSearchMetadata.await().size

    suspend fun countMangaInLibraryWithMissingMetadata(): Int = getFavorites.await().count {
        getSearchMetadata.await(it.id) == null
    }

    suspend fun clearSavedSearches() = debugDatabase.deleteAllSavedSearches()

    suspend fun listAllSources(): String = sourceManager.getAll().joinToString("\n") {
        "${it.id}: ${it.name} (${it.lang.uppercase()})"
    }

    suspend fun listAllSourcesClassName(): String = sourceManager.getAll().joinToString("\n") {
        "${it::class.qualifiedName}: ${it.name} (${it.lang.uppercase()})"
    }

    suspend fun listVisibleSources(): String = visibleSources().joinToString("\n") {
        "${it.id}: ${it.name} (${it.lang.uppercase()})"
    }

    suspend fun listAllHttpSources(): String = sourceManager.getOnlineSources().joinToString("\n") {
        "${it.id}: ${it.name} (${it.lang.uppercase()})"
    }

    suspend fun listVisibleHttpSources(): String {
        val visibleIds = visibleSources().map { it.id }.toSet()
        return sourceManager.getOnlineSources().filter { it.id in visibleIds }.joinToString("\n") {
            "${it.id}: ${it.name} (${it.lang.uppercase()})"
        }
    }

    suspend fun convertAllEhentaiGalleriesToExhentai() = debugDatabase.migrateSource(EH_SOURCE_ID, EXH_SOURCE_ID)

    suspend fun convertAllExhentaiGalleriesToEhentai() = debugDatabase.migrateSource(EXH_SOURCE_ID, EH_SOURCE_ID)

    fun testLaunchEhentaiBackgroundUpdater() {
        EHentaiUpdateWorker.launchBackgroundTest(context)
    }

    fun rescheduleEhentaiBackgroundUpdater() {
        EHentaiUpdateWorker.setupTask(context)
    }

    fun listScheduledJobs(): String = jobScheduler().allPendingJobs.joinToString(",\n") { job ->
        val info = job.extras.getString("EXTRA_WORK_SPEC_ID")?.let {
            context.workManager.getWorkInfoById(UUID.fromString(it)).get()
        }

        if (info != null) {
            """
            {
                id: ${info.id},
                isPeriodic: ${job.extras.getBoolean("EXTRA_IS_PERIODIC")},
                state: ${info.state.name},
                tags: [
                    ${info.tags.joinToString(separator = ",\n                    ")}
                ],
            }
            """.trimIndent()
        } else {
            """
            {
                info: ${job.id},
                isPeriodic: ${job.isPeriodic},
                isPersisted: ${job.isPersisted},
                intervalMillis: ${job.intervalMillis},
            }
            """.trimIndent()
        }
    }

    fun cancelAllScheduledJobs() = jobScheduler().cancelAll()

    suspend fun fixReaderViewerBackupBug() = debugDatabase.resetBrokenReaderFlags()

    suspend fun resetReaderViewerForAllManga() = mangaRepository.resetViewerFlags()

    @OptIn(ExperimentalSerializationApi::class)
    fun exportProtobufScheme() = ProtoBufSchemaGenerator.generateSchemaText(Backup.serializer().descriptor)

    // Both library updaters, since Reikai updates manga and novels in separate jobs.
    fun killLibraryJobs() {
        LibraryUpdateJob.stop(context)
        NovelUpdateJob.stop(context)
    }

    private suspend fun runMigrations(fromVersion: Int): Boolean {
        val jobFactory =
            MigrationJobFactory(MigrationContext(dryrun = false, previousVersion = fromVersion), Migrator.scope)
        val strategy = MigrationStrategyFactory(jobFactory) {}.create(fromVersion, BuildConfig.VERSION_CODE)
        return strategy(migrations.toList()).await()
    }

    // The enabled-sources list repeats a pinned or last-used source in its own section.
    private suspend fun visibleSources() = getEnabledSources.subscribe().first().distinctBy { it.id }

    private fun jobScheduler() = context.getSystemService(JobScheduler::class.java)
}
