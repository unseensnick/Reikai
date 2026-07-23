package reikai.presentation.library

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
}
