package yokai.presentation.library.manga

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.flow.Flow
import yokai.presentation.library.LibraryUpdater

/**
 * Forwards Compose-library refresh calls to the legacy [LibraryUpdateJob] companion API
 * without modifying that class beyond the dynamic-category bug fix in C10. [stop] also
 * dismisses the in-progress library notification, mirroring the legacy controller's
 * Cancel-action body (LibraryController.kt:980-983).
 */
class MangaLibraryUpdater(private val context: Context) : LibraryUpdater {

    override val updateFlow: Flow<Long?> = LibraryUpdateJob.updateFlow

    override fun startNow(category: Category?, mangaToUse: List<LibraryManga>?): Boolean =
        LibraryUpdateJob.startNow(context, category, mangaToUse = mangaToUse)

    override fun stop() {
        LibraryUpdateJob.stop(context)
        NotificationReceiver.dismissNotification(context, Notifications.ID_LIBRARY_PROGRESS)
    }

    override fun isRunning(): Boolean = LibraryUpdateJob.isRunning(context)

    override fun isRunningFlow(): Flow<Boolean> = LibraryUpdateJob.isRunningFlow(context)

    override fun isCategoryInQueue(categoryId: Int?): Boolean =
        LibraryUpdateJob.categoryInQueue(categoryId)
}
