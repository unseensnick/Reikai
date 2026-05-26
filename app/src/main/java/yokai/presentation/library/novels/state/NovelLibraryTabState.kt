package yokai.presentation.library.novels.state

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.ui.library.models.LibraryItem

/**
 * Novel-side parallel of [yokai.presentation.library.LibraryTabState]. Held disjoint from
 * the manga state because the pre-collapse resolve list ([Loaded.libraryNovelForResolve])
 * is concretely typed [List]<[LibraryNovel]> rather than the manga side's [List]<eu.kanade.tachiyomi.data.database.models.LibraryManga>,
 * and generalising the manga sealed class to support both would touch every consumer.
 * Matches Decision #1's "fully separated novel data" stance.
 *
 * Field semantics mirror manga's [Loaded] verbatim; see KDoc there for the WHY on
 * `sortEpoch`, `categorySortOrder`, `collapsedDynamicCategories`,
 * `collapsedDynamicAtBottom`, and `libraryNovelForResolve`.
 */
@Immutable
sealed interface NovelLibraryTabState {
    data object Loading : NovelLibraryTabState

    data class Loaded(
        val library: Map<NovelCategory, List<LibraryItem.Novel>>,
        val totalItemCount: Int,
        val isRunning: Boolean = false,
        val inQueueCategoryIds: Set<Int> = emptySet(),
        val currentCategoryOrder: Int = 0,
        val selection: Set<Long> = emptySet(),
        val sortEpoch: Int = 0,
        val categorySortOrder: Int = 0,
        val collapsedDynamicCategories: Set<String> = emptySet(),
        val collapsedDynamicAtBottom: Boolean = false,
        val libraryNovelForResolve: List<LibraryNovel> = emptyList(),
    ) : NovelLibraryTabState
}
