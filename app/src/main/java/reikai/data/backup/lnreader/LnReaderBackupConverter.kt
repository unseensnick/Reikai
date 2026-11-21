package reikai.data.backup.lnreader

import android.content.Context
import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.create.BackupProtoWriter
import eu.kanade.tachiyomi.data.backup.models.BackupFields
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSource
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreWorker
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * Turns an LNReader backup into a Reikai one in the cache, which the ordinary restore then validates and
 * restores, merging into the library as it does any backup. Library novels come across with their
 * chapters' read state, bookmarks, progress and history, and their categories; LNReader's plugins are
 * listed for the restore to install from the user's repos. Local novels cannot come across, since their
 * files live in LNReader, so they are named instead. See docs/dev/subsystems/data-and-backup.md.
 */
@Inject
class LnReaderBackupConverter(
    private val context: Context,
    private val parser: ProtoBuf,
    private val novelPreferences: NovelPreferences,
) {

    class Converted(val uri: Uri, val skippedLocal: List<String>)

    /** [uri] converted, or null when it is not an LNReader backup. */
    suspend fun convertIfLnReader(uri: Uri): Converted? = withIOContext {
        val reader = LnReaderBackupReader { checkNotNull(context.contentResolver.openInputStream(uri)) }
        if (!reader.isLnReaderBackup()) return@withIOContext null
        val dir = importDir(context).apply { mkdirs() }
        // One that never reached a restore; a restore running or queued may still read its own.
        if (!BackupRestoreWorker.isPending(context.workManager)) dir.listFiles()?.forEach(File::delete)

        val header = try {
            reader.header()
        } catch (e: UnsupportedLnReaderFormat) {
            throw IOException(context.stringResource(MR.strings.restore_lnreader_format_unsupported), e)
        }
        val plugins = header.plugins.associateBy { it.id }
        val zone = TimeZone.currentSystemDefault()
        val skippedLocal = mutableListOf<String>()
        val sources = LinkedHashSet<String>()
        val file = File(dir, "${UUID.randomUUID()}.tachibk")
        GZIPOutputStream(file.outputStream()).use { out ->
            reader.forEachImported(onLocal = { skippedLocal += it }) { novel ->
                sources += novel.pluginId
                out.write(BackupFields.NOVELS, BackupNovel.serializer(), novel.toImportedNovel(header.categories, zone))
            }
            importedCategories(header.categories).forEach {
                out.write(BackupFields.NOVEL_CATEGORIES, BackupNovelCategory.serializer(), it)
            }
            sources.forEach { id ->
                out.write(
                    BackupFields.NOVEL_SOURCES,
                    BackupNovelSource.serializer(),
                    BackupNovelSource(plugins[id]?.name.orEmpty(), id),
                )
            }
            pluginPreferences(header.plugins).forEach {
                out.write(BackupFields.PREFERENCES, BackupPreference.serializer(), it)
            }
            BackupProtoWriter.writeFormatMarkers(out, parser)
        }
        Converted(file.toUri(), skippedLocal)
    }

    // The install records the restore reads to offer LNReader's plugins from the user's repos; it never
    // writes these as settings (AppPreferenceCarry), and adds a seen name only for a source it lacks.
    private fun pluginPreferences(plugins: List<LnReaderPlugin>): List<BackupPreference> {
        if (plugins.isEmpty()) return emptyList()
        return listOf(
            BackupPreference(
                novelPreferences.installedPluginUrls().key(),
                StringSetPreferenceValue(plugins.mapTo(LinkedHashSet()) { it.url }),
            ),
            BackupPreference(
                novelPreferences.installedPluginMetadata().key(),
                StringPreferenceValue(
                    NovelPreferences.encodePluginMetadata(
                        plugins.associate {
                            it.url to LnInstalledPluginMetadata(it.id)
                        },
                    ),
                ),
            ),
            BackupPreference(
                novelPreferences.seenNovelSources().key(),
                StringPreferenceValue(
                    NovelPreferences.encodeSeenNovelSources(
                        plugins.associate { it.id to LnSourceIdentity(it.name, it.iconUrl, it.lang, it.site) },
                    ),
                ),
            ),
        )
    }

    private fun <T> OutputStream.write(field: Int, serializer: SerializationStrategy<T>, value: T) =
        BackupProtoWriter.writeField(this, field, parser.encodeToByteArray(serializer, value))

    companion object {
        private fun importDir(context: Context) = File(context.cacheDir, "lnreader-import")

        /** Deletes [uri] once its restore is over, when it is a converted backup; any other file is the user's. */
        fun discard(context: Context, uri: Uri) {
            if (uri.scheme == "file" && uri.toFile().parentFile == importDir(context)) uri.toFile().delete()
        }
    }
}
