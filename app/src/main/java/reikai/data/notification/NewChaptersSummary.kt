package reikai.data.notification

import android.content.Context
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/** What a new-chapters group summary says, decided before any string is looked up. */
sealed interface NewChaptersSummary {

    /** One entry updated and it may be named. */
    data class One(val title: String) : NewChaptersSummary

    /** A count, then one line per entry (null for one left unnamed); none at all while every entry is hidden. */
    data class Many(val count: Int, val lines: List<String?>) : NewChaptersSummary
}

/** [shownTitles] is one per updated entry, null for an entry the notification must leave unnamed. */
fun newChaptersSummary(shownTitles: List<String?>, hideAll: Boolean): NewChaptersSummary {
    val sole = shownTitles.singleOrNull()
    if (sole != null) return NewChaptersSummary.One(sole.chop(NOTIF_TITLE_MAX_LEN))
    val lines = if (hideAll) emptyList() else shownTitles.map { it?.chop(NOTIF_TITLE_MAX_LEN) }
    return NewChaptersSummary.Many(shownTitles.size, lines)
}

/** Writes [summary] into a group summary notification, as Mihon's library updater words it. */
fun NotificationCompat.Builder.setNewChaptersSummary(context: Context, summary: NewChaptersSummary) {
    when (summary) {
        is NewChaptersSummary.One -> setContentText(summary.title)
        is NewChaptersSummary.Many -> {
            setContentText(
                context.pluralStringResource(
                    MR.plurals.notification_new_chapters_summary,
                    summary.count,
                    summary.count,
                ),
            )
            if (summary.lines.isNotEmpty()) {
                val generic = context.stringResource(MR.strings.notification_new_chapters)
                setStyle(NotificationCompat.BigTextStyle().bigText(summary.lines.joinToString("\n") { it ?: generic }))
            }
        }
    }
}

/** Whether an entry's new-chapters notification offers Download, which queues every one of them at once. */
fun offersDownloadAction(newChapters: Int): Boolean =
    newChapters <= Downloader.CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD

/** An entry's cover, cropped round for a notification's large icon; [data] is any cover model Coil takes. */
suspend fun Context.notificationCover(data: Any): Bitmap? {
    val request = ImageRequest.Builder(this)
        .data(data)
        .transformations(CircleCropTransformation())
        .size(NOTIF_ICON_SIZE)
        .build()
    return imageLoader.execute(request).image?.asDrawable(resources)?.getBitmapOrNull()
}

private const val NOTIF_ICON_SIZE = 192
