package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The library cover's badge toggles, which gate a row's badges the same way for manga and novels.
 * A badge the user turned off is zeroed on the row rather than hidden at draw time, so a merged row
 * built later from these values never lights one either.
 */
data class LibraryBadgePrefs(
    val download: Boolean,
    val unread: Boolean,
    val local: Boolean,
    val language: Boolean,
    val source: Boolean,
) {
    fun downloadBadge(count: Int): Int = if (download) count else 0

    fun unreadBadge(count: Long): Long = if (unread) count else 0

    fun badges(
        downloadCount: Int,
        unreadCount: Long,
        isLocal: Boolean,
        sourceLanguage: String,
        sourceBadge: SourceBadge,
        coverSourceId: String? = null,
    ) = LibraryItem.Badges(
        downloadCount = downloadBadge(downloadCount),
        unreadCount = unreadBadge(unreadCount),
        isLocal = local && isLocal,
        sourceLanguage = if (language) sourceLanguage else "",
        source = sourceBadge.takeIf { source },
        coverSourceId = coverSourceId,
    )
}

fun libraryBadgePrefsFlow(
    libraryPreferences: LibraryPreferences,
    reikaiLibraryPreferences: ReikaiLibraryPreferences,
): Flow<LibraryBadgePrefs> = combine(
    libraryPreferences.downloadBadge.changes(),
    libraryPreferences.unreadBadge.changes(),
    libraryPreferences.localBadge.changes(),
    libraryPreferences.languageBadge.changes(),
    reikaiLibraryPreferences.sourceBadge.changes(),
    ::LibraryBadgePrefs,
)
