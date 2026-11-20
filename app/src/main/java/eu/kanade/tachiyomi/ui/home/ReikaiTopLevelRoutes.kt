package eu.kanade.tachiyomi.ui.home

import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import eu.kanade.tachiyomi.R
import kotlinx.serialization.Serializable
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.QueryStats
import reikai.presentation.components.cachedAnimatedVector
import reikai.presentation.recents.ShowsUpdatesBadge
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

// Reikai's own bottom-bar tabs, beside upstream's in the sealed TopLevelRoute, which is why they live in its package.

/** Updates and History as one tab, with the four modes inside it, when the combined-recents setting is on. */
@Serializable
data object RecentsTabRoute : TopLevelRoute, ShowsUpdatesBadge {
    @Composable
    override fun options(isSelected: Boolean): TabOptions {
        val image = cachedAnimatedVector(R.drawable.animated_updates)
        return TabOptions(
            title = stringResource(MR.strings.label_recents),
            icon = rememberAnimatedVectorPainter(image, isSelected),
        )
    }
}

/** Statistics in the bottom bar, when the Statistics shortcut setting asks for a tab. */
@Serializable
data object StatsTabRoute : TopLevelRoute {
    @Composable
    override fun options(isSelected: Boolean): TabOptions = TabOptions(
        title = stringResource(MR.strings.label_stats),
        icon = rememberVectorPainter(MaterialSymbols.Rounded.QueryStats),
    )
}
