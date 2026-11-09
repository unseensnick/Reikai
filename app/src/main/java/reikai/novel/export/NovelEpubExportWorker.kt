package reikai.novel.export

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.work.CoroutineWorker
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
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import mihon.core.migration.Migrator
import reikai.data.notification.hiddenEntryIds
import reikai.data.novel.NovelStatusCode
import reikai.domain.entry.EntryId
import reikai.domain.entry.GetEntryCustomInfo
import reikai.domain.library.bookLanguage
import reikai.domain.library.entryLanguage
import reikai.domain.manga.AdultContentChecker
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.local.LocalNovelLanguageRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.asNovelCover
import reikai.domain.novel.model.withCustomInfo
import reikai.domain.novel.ownersOf
import reikai.novel.download.NovelDownloadedTexts
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.EpubBook
import reikai.novel.source.NovelSourceManager
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.IOException
import java.io.OutputStream
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

    @Inject private lateinit var getEntryCustomInfo: GetEntryCustomInfo

    @Inject private lateinit var securityPreferences: SecurityPreferences

    @Inject private lateinit var adultChecker: AdultContentChecker

    // WorkManager's own cancel intent for this run, so the notification action needs no receiver. A book
    // cut short is deleted where it is written, so a cancel leaves no unreadable file behind.
    private val progress by lazy {
        context.notificationBuilder(Notifications.CHANNEL_COMMON) {
            setSmallIcon(MaterialSymbolsR.drawable.rounded_book)
            setOngoing(true)
            setOnlyAlertOnce(true)
            addAction(
                MaterialSymbolsR.drawable.rounded_close,
                context.stringResource(MR.strings.action_cancel),
                context.workManager.createCancelPendingIntent(id),
            )
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        Notifications.ID_EPUB_EXPORT_PROGRESS,
        progress.build(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result {
        Migrator.await()
        // One export at a time, each its own work: a WorkManager chain would cancel or fail every export
        // queued behind one the user cancelled or that failed, and the shared progress notice holds one run.
        return exportLock.withLock {
            setForegroundSafely()
            // WorkManager clears the progress only with a foreground service it started; a refused start leaves it.
            try {
                exportAll()
            } finally {
                context.cancelNotification(Notifications.ID_EPUB_EXPORT_PROGRESS)
            }
        }
    }

    private suspend fun exportAll(): Result {
        val ids = inputData.getLongArray(KEY_NOVEL_IDS)?.toList().orEmpty()
        val target = inputData.getString(KEY_TARGET)?.let(Uri::parse) ?: return Result.failure()
        val toFolder = inputData.getBoolean(KEY_TO_FOLDER, false)
        val writer = EpubWriter(includePictures = inputData.getBoolean(KEY_PICTURES, true))
        val from = inputData.getInt(KEY_FROM, 0)
        val to = inputData.getInt(KEY_TO, 0)

        // As the library shows them, so the book carries what the user put in Edit info.
        val novels = ids.mapNotNull { novelRepository.getById(it) }
            .map { it.withCustomInfo(getEntryCustomInfo.await(EntryId.Novel(it.id))) }
        val hidden = hiddenEntryIds(
            novels,
            securityPreferences.hideNotificationContent.get(),
            securityPreferences.hideAdultNotificationContent.get(),
            Novel::id,
            adultChecker::adultNovelIdsAmong,
        )
        val names = EpubFileNames()
        var written = 0
        val failed = mutableListOf<Novel>()
        for (novel in novels) {
            val shownTitle = if (novel.id in hidden) {
                context.stringResource(MR.strings.export_epub_running)
            } else {
                context.stringResource(MR.strings.export_epub_progress, novel.title)
            }
            val fileName = names.next(novel.title).takeIf { toFolder }
            val outcome = runCatchingCancellable { export(novel, target, fileName, writer, from, to, shownTitle) }
                .onFailure { logcat(LogPriority.ERROR, it) { "Could not export novel ${novel.id}" } }
            if (outcome.getOrDefault(false)) written++ else failed += novel
        }
        context.notify(Notifications.ID_EPUB_EXPORT_RESULT, Notifications.CHANNEL_COMMON) {
            setSmallIcon(MaterialSymbolsR.drawable.rounded_book)
            setContentTitle(
                when {
                    failed.isEmpty() -> context.pluralStringResource(MR.plurals.export_epub_done, written, written)
                    failed.any { it.id in hidden } ->
                        context.pluralStringResource(MR.plurals.export_epub_failed_count, failed.size, failed.size)
                    else -> context.stringResource(MR.strings.export_epub_failed, failed.joinToString { it.title })
                },
            )
            setAutoCancel(true)
        }
        return if (written > 0 || failed.isEmpty()) Result.success() else Result.failure()
    }

    /**
     * False when [novel] has no chapter on the device to put in a book. [fileName] names the book in the folder
     * [target]; null writes the file [target] itself. [shownTitle] is what the progress notice may say.
     */
    private suspend fun export(
        novel: Novel,
        target: Uri,
        fileName: String?,
        writer: EpubWriter,
        from: Int,
        to: Int,
        shownTitle: String,
    ): Boolean {
        val onDevice = downloadedTexts.chaptersOf(novel.id, sourceScoped = false).onDisk
        val isRun = from > 0 && to >= from
        val chosen = if (isRun) onDevice.drop(from - 1).take(to - from + 1) else onDevice
        if (chosen.isEmpty()) return false
        val owners = novelRepository.ownersOf(chosen)
        val book = EpubExportBook(
            title = novel.title,
            author = novel.author,
            description = novel.description,
            // A local novel's own language, else its source's; a local source's own is a placeholder.
            language = bookLanguage(
                entryLanguage(
                    LocalNovelSource.isLocal(novel.source),
                    localNovelLanguages.get(novel.url),
                    sourceManager.langOf(novel.source),
                ),
            ),
            genres = novel.genre.orEmpty(),
            identifier = "${EpubBook.REIKAI_IDENTIFIER_PREFIX}novel:${novel.source}:${novel.url}",
            cover = cover(novel),
            artist = novel.artist,
            status = NovelStatusCode.toSourceString(novel.status.toInt()),
            // A run is one volume of the novel, placed by its first chapter so several runs import in order.
            seriesPosition = from.takeIf { isRun },
            chapters = chosen.map { chapter ->
                ExportChapter(chapter.name) {
                    downloadedTexts.storedHtml(chapter, owners) ?: throw IOException("Missing ${chapter.name}")
                }
            },
        )
        val write: suspend (OutputStream) -> Unit = { out ->
            writer.write(book, out) { done ->
                progress.setContentTitle(shownTitle).setProgress(chosen.size, done, false)
                context.notify(Notifications.ID_EPUB_EXPORT_PROGRESS, progress.build())
            }
        }
        if (fileName != null) {
            val folder = UniFile.fromUri(context, target) ?: throw IOException("No folder")
            folder.writeBookNamed(context, fileName, write)
        } else {
            // A file the picker named, which the reader chose to create or to replace.
            (UniFile.fromUri(context, target) ?: throw IOException("No file")).writeOrDelete(context, write)
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
        private val exportLock = Mutex()
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
            context.workManager.enqueue(request)
        }
    }
}
