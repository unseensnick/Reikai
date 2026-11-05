package reikai.presentation.stats

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.stats.StatsContent
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.QueryStats
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Statistics in the bottom bar, when the Statistics shortcut setting asks for a tab. */
data object StatsTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 4u,
            title = stringResource(MR.strings.label_stats),
            icon = rememberVectorPainter(MaterialSymbols.Rounded.QueryStats),
        )

    @Composable
    override fun Content() {
        StatsContent(navigateUp = null)
    }
}
