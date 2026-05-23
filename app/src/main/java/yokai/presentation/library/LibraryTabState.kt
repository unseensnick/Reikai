package yokai.presentation.library

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.ui.library.models.LibraryItem

/**
 * Tab-agnostic library state. Each tab (Manga, Novels) has its own screen model that emits this
 * type, parameterized by the variant of [LibraryItem] it renders.
 *
 * Covariant in [T] so the shared composable can accept `LibraryTabState<out LibraryItem>` and
 * render either tab's emission.
 */
@Immutable
sealed interface LibraryTabState<out T : LibraryItem> {
    data object Loading : LibraryTabState<Nothing>

    data class Loaded<T : LibraryItem>(
        val library: Map<Category, List<T>>,
        val totalItemCount: Int,
    ) : LibraryTabState<T>
}
