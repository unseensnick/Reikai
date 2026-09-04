package eu.kanade.presentation.browse.components

import androidx.compose.runtime.Composable
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.CollectionsBookmark
import tachiyomi.presentation.core.components.Badge

// RK: public so the net-new novel browse grid reuses the same in-library badge
@Composable
fun InLibraryBadge(enabled: Boolean) {
    if (enabled) {
        Badge(
            imageVector = MaterialSymbols.Rounded.CollectionsBookmark,
        )
    }
}
