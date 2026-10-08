package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupFields
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSource
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.PreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.novelSourceName
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.serialization.protobuf.ProtoBuf
import reikai.domain.novel.NovelPreferences
import reikai.novel.install.LnPluginLoadFailure
import reikai.novel.source.NovelSourceManager
import tachiyomi.domain.source.service.SourceManager

@Inject
class BackupFileValidator(
    // RK: kept, unlike upstream: the streaming reader needs it where upstream decodes the whole file.
    private val context: Context,
    private val sourceManager: SourceManager,
    private val trackerManager: TrackerManager,
    // RK: novels validate against their own source registry + the shared tracker manager.
    private val novelSourceManager: NovelSourceManager,
    private val parser: ProtoBuf,
    // RK --> what is installed here, against the backup's extension apps and plugins
    private val extensionManager: ExtensionManager,
    private val novelPreferences: NovelPreferences,
    // RK <--
) {

    /**
     * Checks for critical backup file data.
     *
     * RK: streams the backup field by field (via [BackupProtoReader]) and decodes only the manga,
     * source, and novel entries it needs, instead of re-inflating the whole file, which OOMs on a
     * large backup (unseensnick/Reikai#53).
     *
     * @return List of missing sources or missing trackers. RK: and the extensions to install.
     */
    suspend fun validate(uri: Uri): Results {
        // RK --> the novel registry is empty until something loads the plugins, and a cold open straight to
        // restore is one, so without this every installed novel source reports as missing.
        novelSourceManager.ensureLoaded()

        val (backupSources, trackerIds, novelSources, novelSourceNames, backupExtensions, pluginPreferences) =
            scan(uri)
        // RK <--

        val sources = backupSources.associate { it.sourceId to it.name } // RK: streamed
        val missingSources = sources
            .filterKeys { sourceManager.get(it) == null }
            .map { (id, name) ->
                // Some backups store the id as the name, and sources without a stub were backed up with no name
                if (name.isBlank() || name.toLongOrNull() != null) {
                    sourceManager.getOrStub(id).toString()
                } else {
                    name
                }
            }
            .distinct()
            .sorted()

        val missingTrackers = trackerIds // RK: streamed, manga and novel ids in one set
            .mapNotNull { trackerManager.get(it) }
            .filter { !it.isLoggedIn }
            .map { it.name }
            .sorted()

        // RK --> fold in novel sources the restore can't satisfy, so the pre-restore warning covers
        // novels too. A missing novel source shows by the name the backup recorded, or by its id in a
        // backup older than that list.
        val missingNovelSources = novelSources
            .filter { novelSourceManager.get(it) == null }
            .map(novelSourceNames::novelSourceName)

        return Results(
            (missingSources + missingNovelSources).distinct().sorted(),
            missingTrackers,
            (missingExtensionApps(backupExtensions) + missingPlugins(pluginPreferences)).distinct().sorted(),
        )
        // RK <--
    }

    // RK -->

    /**
     * Throws [IllegalStateException] when the file at [uri] does not decode, and resolves nothing: the
     * check a freshly written backup needs. Resolving novel sources loads every installed plugin and can
     * fetch over the network, which [validate] does only because the restore screen shows its answer.
     */
    suspend fun checkReadable(uri: Uri) {
        scan(uri)
    }

    // A restore installs neither extension apps nor plugins, since a backup can be anyone's file, so the
    // restore screen lists the backup's ones this install lacks for the user to install.
    private suspend fun missingExtensionApps(backupExtensions: List<BackupExtension>): List<String> {
        if (backupExtensions.isEmpty()) return emptyList()
        // Read past the adult-source gate, which hides an app without uninstalling it; untrusted and failing
        // apps are installed all the same.
        val installed = with(extensionManager) {
            getLoadedExtensions() + getNotLoadedExtensions() + getLoadedNovelExtensions() +
                getNotLoadedNovelExtensions()
        }.mapTo(HashSet()) { it.pkgName }
        return backupExtensions.filterNot { it.pkgName in installed }.map { it.name }
    }

    // A plugin installed from another repo has another URL, so its id answers too.
    private fun missingPlugins(backup: Map<String, PreferenceValue>): List<String> {
        val urls = (backup[novelPreferences.installedPluginUrls().key()] as? StringSetPreferenceValue)?.value
        if (urls.isNullOrEmpty()) return emptyList()
        fun stored(key: String) = (backup[key] as? StringPreferenceValue)?.value
        val metadata = stored(novelPreferences.installedPluginMetadata().key())
            ?.let(NovelPreferences::decodePluginMetadata).orEmpty()
        val seen = stored(novelPreferences.seenNovelSources().key())
            ?.let(NovelPreferences::decodeSeenNovelSources).orEmpty()
        val installedUrls = novelPreferences.installedPluginUrls().get()
        val installedIds = novelPreferences.installedPluginMetadata().get().values.mapTo(HashSet()) { it.pluginId }
        return urls
            .filterNot { it in installedUrls || metadata[it]?.pluginId in installedIds }
            .map { LnPluginLoadFailure.pluginName(it, seen[metadata[it]?.pluginId]) }
    }

    // Streams the backup field by field and decodes only what validation reads.
    private suspend fun scan(uri: Uri): Scanned {
        val scanned = Scanned()
        val pluginKeys = with(novelPreferences) {
            setOf(installedPluginUrls().key(), installedPluginMetadata().key(), seenNovelSources().key())
        }
        try {
            BackupProtoReader(context).read(uri) { fieldNumber, data ->
                when (fieldNumber) {
                    BackupFields.MANGA -> parser.decodeFromByteArray(BackupManga.serializer(), data)
                        .tracking.forEach { scanned.trackerIds.add(it.syncId.toLong()) }
                    BackupFields.SOURCES ->
                        scanned.backupSources.add(parser.decodeFromByteArray(BackupSource.serializer(), data))
                    BackupFields.NOVELS -> parser.decodeFromByteArray(BackupNovel.serializer(), data).let { novel ->
                        scanned.novelSources.add(novel.source)
                        novel.tracking.forEach { scanned.trackerIds.add(it.trackerId) }
                    }
                    BackupFields.NOVEL_SOURCES -> parser.decodeFromByteArray(BackupNovelSource.serializer(), data).let {
                        scanned.novelSourceNames[it.sourceId] = it.name
                    }
                    BackupFields.EXTENSIONS ->
                        scanned.backupExtensions.add(parser.decodeFromByteArray(BackupExtension.serializer(), data))
                    BackupFields.PREFERENCES ->
                        parser.decodeFromByteArray(BackupPreference.serializer(), data).let { (key, value) ->
                            if (key in pluginKeys) scanned.pluginPreferences[key] = value
                        }
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
        return scanned
    }

    private data class Scanned(
        val backupSources: MutableList<BackupSource> = mutableListOf(),
        // Both content types share one tracker registry, so one id set covers both.
        val trackerIds: MutableSet<Long> = mutableSetOf(),
        val novelSources: MutableSet<String> = mutableSetOf(),
        // Field 717, absent from a backup made before it existed.
        val novelSourceNames: MutableMap<String, String> = mutableMapOf(),
        val backupExtensions: MutableList<BackupExtension> = mutableListOf(),
        // The App settings entries that record the installed plugins, by key.
        val pluginPreferences: MutableMap<String, PreferenceValue> = mutableMapOf(),
    )
    // RK <--

    data class Results(
        val missingSources: List<String>,
        val missingTrackers: List<String>,
        val missingExtensions: List<String>, // RK: extension apps and plugins, neither installed by a restore
    )
}
