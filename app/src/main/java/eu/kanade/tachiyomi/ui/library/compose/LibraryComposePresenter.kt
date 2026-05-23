package eu.kanade.tachiyomi.ui.library.compose

import eu.kanade.tachiyomi.ui.base.presenter.StateCoroutinePresenter

/**
 * No-op shell. Required by `BaseCoroutineController`'s presenter type parameter; the actual
 * library state lives in `MangaLibraryScreenModel` (Voyager). This class can be deleted once
 * the controller migrates off `BaseCoroutineController`, which is scoped to Phase 8 when the
 * tabbed shell takes over the toolbar.
 */
class LibraryComposePresenter :
    StateCoroutinePresenter<Unit, LibraryComposeController>(Unit)
