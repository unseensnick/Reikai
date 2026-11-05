package reikai.novel.export

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import mihon.core.migration.Migrator
import reikai.data.novel.NovelStatusCode
import reikai.domain.library.entryLanguage
import reikai.domain.library.localLanguage
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.local.LocalNovelLanguageRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.asNovelCover
import reikai.domain.novel.ownersOf
import reikai.novel.download.NovelDownloadedTexts
import reikai.novel.local.LocalNovelSource
import reikai.novel.source.NovelSourceManager
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.IOException
import mihon.icons.materialsymbols.R as MaterialSymbolsR

/**
 * Writes novels as EPUB files: the chapters on the device (downloaded, or a local novel's own) in reading
 * order, with the novel's details and cover. One novel goes to the file the user named; several go into the
 * folder they picked, a file each. Foreground, since a long novel takes a while and must not die with the app.
 */
class NovelEpubExportWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val graph: AppGraph = context.metroGraph()

    init {
        graph.inject(this)
    }

    @Inject private lateinit var novelRepository: NovelRepository

    @Inject private lateinit var downloadedTexts: NovelDownloadedTexts

    @Inject private lateinit var sourceManager: NovelSourceManager

    @Inject private lateinit var localNovelLanguages: LocalNovelLanguageRepository

    private val progress = context.notificationBuilder(Notifications.CHANNEL_COMMON) {
        setSmallIcon(MaterialSymbolsR.drawable.rounded_book)
        setOngoing(true)
        setOnlyAlertOnce(true)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        Notifications.ID_EPUB_EXPORT_PROGRESS,
        progress.build(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result {
        Migrator.await()
        setForegroundSafely()
        // WorkManager clears the progress only with a foreground service it started; a refused start leaves it.
        return try {
            exportAll()
        } finally {
            context.cancelNotification(Notifications.ID_EPUB_EXPORT_PROGRESS)
        }
    }

    private suspend fun exportAll(): Result {
        val ids = inputData.getLongArray(KEY_NOVEL_IDS)?.toList().orEmpty()
        val target = inputData.getString(KEY_TARGET)?.let(Uri::parse) ?: return Result.failure()
        val toFolder = inputData.getBoolean(KEY_TO_FOLDER, false)
        val writer = EpubWriter(includePictures = inputData.getBoolean(KEY_PICTURES, true))
        val from = inputData.getInt(KEY_FROM, 0)
        val to = inputData.getInt(KEY_TO, 0)

        var written = 0
        val failed = mutableListOf<String>()
        for (id in ids) {
            val novel = novelRepository.getById(id) ?: continue
            val outcome = runCatchingCancellable { export(novel, target, toFolder, writer, from, to) }
                .onFailure { logcat(LogPriority.ERROR, it) { "Could not export ${novel.title}" } }
            if (outcome.getOrDefault(false)) written++ else failed += novel.title
        }
        context.notify(Notifications.ID_EPUB_EXPORT_RESULT, Notifications.CHANNEL_COMMON) {
            setSmallIcon(MaterialSymbolsR.drawable.rounded_book)
            setContentTitle(
                if (failed.isEmpty()) {
                    context.pluralStringResource(MR.plurals.export_epub_done, written, written)
                } else {
                    context.stringResource(MR.strings.export_epub_failed, failed.joinToString())
                },
            )
            setAutoCancel(true)
        }
        return if (written > 0 || failed.isEmpty()) Result.success() else Result.failure()
    }

    /** False when [novel] has no chapter on the device to put in a book. */
    private suspend fun export(
        novel: Novel,
        target: Uri,
        toFolder: Boolean,
        writer: EpubWriter,
        from: Int,
        to: Int,
    ): Boolean {
        val onDevice = downloadedTexts.chaptersOf(novel.id, sourceScoped = false).onDisk
        val chosen = if (from > 0 && to >= from) onDevice.drop(from - 1).take(to - from + 1) else onDevice
        if (chosen.isEmpty()) return false
        val owners = novelRepository.ownersOf(chosen)
        val book = EpubExportBook(
            title = novel.title,
            author = novel.author,
            description = novel.description,
            // A local novel's own language, else its source's; a local source's own is a placeholder.
            language = entryLanguage(
                LocalNovelSource.isLocal(novel.source),
                localLanguage(localNovelLanguages.getAll()[novel.url]),
                sourceManager.langOf(novel.source),
            ).takeUnless { it.isBlank() || it == "other" },
            genres = novel.genre.orEmpty(),
            identifier = "urn:reikai:novel:${novel.source}:${novel.url}",
            cover = cover(novel),
            artist = novel.artist,
            status = NovelStatusCode.toSourceString(novel.status.toInt()),
            chapters = chosen.map { chapter ->
                ExportChapter(chapter.name) {
                    downloadedTexts.storedHtml(chapter, owners) ?: throw IOException("Missing ${chapter.name}")
                }
            },
        )
        val file = if (toFolder) {
            val folder = UniFile.fromUri(context, target) ?: throw IOException("No folder")
            val name = DiskUtil.buildValidFilename("${novel.title}.epub")
            folder.findFile(name) ?: folder.createFile(name)
        } else {
            UniFile.fromUri(context, target)
        } ?: throw IOException("No file")
        file.writeOrDelete(context) { out ->
            writer.write(book, out) { done ->
                progress
                    .setContentTitle(context.stringResource(MR.strings.export_epub_progress, novel.title))
                    .setProgress(chosen.size, done, false)
                context.notify(Notifications.ID_EPUB_EXPORT_PROGRESS, progress.build())
            }
        }
        return true
    }

    /** Loaded as the library draws it, so a custom cover, a local series' own file and a cached one all count. */
    private suspend fun cover(novel: Novel): ExportPicture? {
        val request = ImageRequest.Builder(context)
            .data(novel.asNovelCover())
            .size(Size.ORIGINAL)
            .allowHardware(false)
            .build()
        return context.imageLoader.execute(request).image
            ?.asDrawable(context.resources)
            ?.getBitmapOrNull()
            ?.let(::coverPicture)
    }

    companion object {
        private const val WORK_NAME = "NovelEpubExport"
        private const val KEY_NOVEL_IDS = "novel_ids"
        private const val KEY_TARGET = "target"
        private const val KEY_TO_FOLDER = "to_folder"
        private const val KEY_PICTURES = "pictures"
        private const val KEY_FROM = "from"
        private const val KEY_TO = "to"

        /**
         * Exports [novelIds] to [target]: a file for one novel, a folder ([toFolder]) for several. [from] and
         * [to] pick a run of the chapters on the device by position, 1 first; 0 exports them all.
         */
        fun start(
            context: Context,
            novelIds: List<Long>,
            target: Uri,
            toFolder: Boolean,
            includePictures: Boolean,
            from: Int = 0,
            to: Int = 0,
        ) {
            val request = OneTimeWorkRequestBuilder<NovelEpubExportWorker>()
                .addTag(WORK_NAME)
                .setInputData(
                    workDataOf(
                        KEY_NOVEL_IDS to novelIds.toLongArray(),
                        KEY_TARGET to target.toString(),
                        KEY_TO_FOLDER to toFolder,
                        KEY_PICTURES to includePictures,
                        KEY_FROM to from,
                        KEY_TO to to,
                    ),
                )
                .build()
            context.workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
