package reikai.presentation.migrate.flow

import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import reikai.domain.novel.model.Novel
import reikai.presentation.browse.EntryBrowseItemUi
import reikai.presentation.browse.toEntryBrowseUi
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.domain.manga.model.Manga

/** Per-type mapping into the shared browse cell; the one place the flow UI branches on a handle. */
internal fun MigrationCandidate.toBrowseUi(): EntryBrowseItemUi = when (val h = handle) {
    is Manga -> h.toEntryBrowseUi()
    is NovelCandidateHandle -> h.item.toEntryBrowseUi(inLibrary = false, site = h.site)
    else -> error("unknown migration candidate handle")
}

/** Open a candidate's details page (the long-press verify affordance). */
internal fun MigrationCandidate.openDetails(navigator: Navigator) {
    when (val h = handle) {
        is Manga -> navigator.push(MangaScreen(h.id))
        is NovelCandidateHandle -> navigator.push(NovelScreen(sourceKey, h.item.path))
    }
}

/** Open a favorites-picker row's details page (the cover-tap affordance). */
internal fun MigrationFavorite.openDetails(navigator: Navigator) {
    when (val p = payload) {
        is Manga -> navigator.push(MangaScreen(p.id))
        is Novel -> navigator.push(NovelScreen(p.source, p.url))
    }
}
