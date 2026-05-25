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
        /**
         * Manga IDs in the merge group represented by this entry, including the primary's own ID.
         * Empty when this entry is standalone. Populated by Phase 6's `MangaLibraryGrouping` when
         * collapsing merged manga; the underlying state is written by Phase 5's merge / unmerge
         * actions via `preferences.mangaManualMerges`. Drives the count badge when `size > 1`,
         * and replaces Phase 5's prefs-lookup expansion in
         * `MangaLibraryScreenModel.expandSelectionWithMergedSiblings`.
         */
        val relatedMangaIds: LongArray = LongArray(0),
    ) : LibraryItem {

        // LongArray properties use reference equality in the data class default, which would
        // defeat the @Immutable skip-on-equal recompose pass whenever the screen model re-emits
        // state with a structurally identical merge group (the array reference is fresh on every
        // emission). Override so contents drive equality instead.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Manga) return false
            if (libraryManga != other.libraryManga) return false
            if (isLocal != other.isLocal) return false
            if (downloadCount != other.downloadCount) return false
            if (unreadCount != other.unreadCount) return false
            if (language != other.language) return false
            if (!relatedMangaIds.contentEquals(other.relatedMangaIds)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = libraryManga.hashCode()
            result = 31 * result + isLocal.hashCode()
            result = 31 * result + downloadCount.hashCode()
            result = 31 * result + unreadCount.hashCode()
            result = 31 * result + language.hashCode()
            result = 31 * result + relatedMangaIds.contentHashCode()
            return result
        }
    }
}
