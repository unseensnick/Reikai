package reikai.presentation.browse.source

import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import reikai.domain.source.SourceKey

/** The settings screen of the source at [this] key, the one place either type's is built. */
fun SourceKey.preferencesScreen(): SourcePreferencesScreen = when (this) {
    is SourceKey.Manga -> SourcePreferencesScreen(id)
    is SourceKey.Novel -> SourcePreferencesScreen.forNovel(id)
}
