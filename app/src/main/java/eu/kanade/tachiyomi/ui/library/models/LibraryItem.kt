package eu.kanade.tachiyomi.ui.library.models

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.data.database.models.LibraryManga

sealed interface LibraryItem {
    @Immutable
    data class Blank(val mangaCount: Int = 0) : LibraryItem

    @Immutable
    data class Hidden(val title: String, val hiddenItems: List<LibraryItem>) : LibraryItem

    /**
     * Marked [Immutable] so Compose can skip recomposes when the same item is re-emitted by the
     * screen model. Stability inference can not prove this on its own because [LibraryManga.manga]
     * is the deprecated [eu.kanade.tachiyomi.domain.manga.models.Manga] interface; the annotation
     * promises that we treat the instance as immutable in practice (covers, badges, etc. are
     * recomputed into new [Manga] copies rather than mutated in place on the Compose path).
     */
    @Immutable
    data class Manga(
        val libraryManga: LibraryManga,
        val isLocal: Boolean = false,
        val downloadCount: Long = -1,
        val unreadCount: Long = -1,
        val language: String = "",
    ) : LibraryItem
}
