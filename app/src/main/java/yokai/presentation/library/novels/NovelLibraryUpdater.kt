package yokai.presentation.library.novels

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.data.library.NovelUpdateJob
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.flow.Flow

/**
 * Novel-side parallel of [yokai.presentation.library.manga.MangaLibraryUpdater]. Forwards
 * Compose-library refresh calls to the [NovelUpdateJob] companion API; [stop] also dismisses
 * the in-progress notification, mirroring the manga side.
 *
 * Diverges from the Phase 7 plan in one place: the plan said "implements the existing
 * `LibraryUpdater` interface." The interface's [yokai.presentation.library.LibraryUpdater.startNow]
 * is typed in [eu.kanade.tachiyomi.data.database.models.Category] /
 * [eu.kanade.tachiyomi.data.database.models.LibraryManga] (manga types), which novels can't
 * polymorphically share. Keeping this as a concrete class with novel-typed methods follows the
 * "fully separated novel data" decision (Decision #1) and the YAGNI default; the future Compose
 * novel screen consumes it directly. If a second implementation ever appears (a remote sync
 * client?), extract an interface then.
 */
class NovelLibraryUpdater(private val context: Context) {

    val updateFlow: Flow<Long?> = NovelUpdateJob.updateFlow

    fun startNow(category: NovelCategory? = null, novelsToUse: List<LibraryNovel>? = null): Boolean =
        NovelUpdateJob.startNow(context, category, novelsToUse = novelsToUse)

    fun stop() {
        NovelUpdateJob.stop(context)
        NotificationReceiver.dismissNotification(context, Notifications.ID_NOVEL_LIBRARY_PROGRESS)
    }

    fun isRunning(): Boolean = NovelUpdateJob.isRunning(context)

    fun isRunningFlow(): Flow<Boolean> = NovelUpdateJob.isRunningFlow(context)

    fun isCategoryInQueue(categoryId: Int?): Boolean = NovelUpdateJob.categoryInQueue(categoryId)
}
