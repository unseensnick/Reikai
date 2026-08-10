package reikai.presentation.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import eu.kanade.presentation.category.visualName
import tachiyomi.domain.category.model.Category

/**
 * One section of the assembled library: a real DB category, or a synthetic bucket from dynamic
 * grouping (by source, tag, author, language, status or tracking status).
 *
 * A dynamic bucket used to be a [Category] with a negative id, so five hand-written guards were all
 * that kept one out of a category write, and the first bucket of every grouping sat on id -1, which
 * NovelUpdateJob reads as "update the whole library". [realCategory] replaces those guards.
 */
@Immutable
sealed interface LibraryBucket {

    /**
     * Stable identity: the render key, the assembly's bucket lookup, the range-select anchor and the
     * collapse-preference key. The two cases never share one list (grouping is by category or dynamic,
     * never both), so the id space and the name space cannot collide.
     */
    val key: String

    /** The DB category, or null for a dynamic group. Every category-scoped write goes through this. */
    val realCategory: Category?

    @Immutable
    data class Real(val category: Category) : LibraryBucket {
        override val key: String = category.id.toString()
        override val realCategory: Category = category
    }

    /**
     * [key] is the normalized encoded bucket name, and it is PERSISTED in `collapsed_dynamic_categories`,
     * so its format is an upgrade constraint rather than an implementation detail. [LibraryDynamicGrouping]
     * owns how it is built.
     */
    @Immutable
    data class Dynamic(override val key: String, val label: String) : LibraryBucket {
        override val realCategory: Category? = null
    }
}

/** Header, tab and picker label: the Default category is localized, everything else names itself. */
val LibraryBucket.visualLabel: String
    @Composable get() = when (this) {
        is LibraryBucket.Real -> category.visualName
        is LibraryBucket.Dynamic -> label
    }
