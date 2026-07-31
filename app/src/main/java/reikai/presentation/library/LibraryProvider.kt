package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.flow.Flow
import reikai.domain.library.ContentType

/**
 * One content type's half of the library. A provider owns its own favourites flow and its own action
 * verbs (read, download, delete, categories) and exposes them through the neutral [LibraryBehavior]
 * seam. The manga provider is the live Mihon `LibraryScreenModel` behind its adapter, so it keeps
 * syncing with upstream instead of being reimplemented in the shared layer.
 *
 * [LibraryEngine] composes the providers, so supporting another content type means adding a provider
 * rather than another content-type branch in the tab.
 */
interface LibraryProvider : LibraryBehavior {
    val contentType: ContentType

    /**
     * This content type's settings sheet, described rather than rendered, so one shared sheet serves both.
     * It sits here rather than on [LibraryBehavior] because it is per-content-type data, not an action on
     * entries, and because a mixed view has no single settings scope to answer with.
     */
    val settings: LibrarySettingsBinding

    /**
     * This content type's library rows: filtered, search-matched and merge-collapsed, but unsorted and
     * unbucketed, with the custom-info overlay deliberately NOT applied (it is applied only at the display
     * read). Filtering stays per provider (each reads its own repositories and source manager); everything
     * downstream of these rows (concatenation, the chip predicate, bucketing, per-category sort) is the
     * shared assembly's job. Cold on purpose: nothing may resolve a scope at construction, and the engine
     * collects it only once assembly lands.
     */
    val rows: Flow<List<LibraryItem>>
}
