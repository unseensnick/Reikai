package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.util.system.LocaleHelper
import reikai.presentation.browse.components.SourceRowContent
import tachiyomi.domain.source.model.Source

@Composable
fun BaseSourceItem(
    source: Source,
    modifier: Modifier = Modifier,
    showLanguageInContent: Boolean = true,
    onClickItem: () -> Unit = {},
    onLongClickItem: () -> Unit = {},
    // RK --> content-type badge, beside the name, drawn by the shared list when it holds both types,
    //     and the heading and language line that list words for both types alike.
    badge: @Composable () -> Unit = {},
    title: String = source.name,
    languageLabel: String? = null,
    // RK <--
    icon: @Composable RowScope.(Source) -> Unit = defaultIcon,
    action: @Composable RowScope.(Source) -> Unit = {},
    content: @Composable RowScope.(Source, String?) -> Unit = { _, lang ->
        DefaultContent(title, lang, badge)
    },
) {
    val sourceLangString = (
        /* RK */ languageLabel
            ?: LocaleHelper.getSourceDisplayName(source.lang, LocalContext.current)
        ).takeIf {
        showLanguageInContent
    }
    BaseBrowseItem(
        modifier = modifier,
        onClickItem = onClickItem,
        onLongClickItem = onLongClickItem,
        icon = { icon.invoke(this, source) },
        action = { action.invoke(this, source) },
        content = { content.invoke(this, source, sourceLangString) },
    )
}

private val defaultIcon: @Composable RowScope.(Source) -> Unit = { source ->
    SourceIcon(source = source, modifier = Modifier.size(48.dp))
}

// RK: was a val, now a function so the badge slot and title above can reach the name row. The text
// is SourceRowContent, which the novel source rows draw too.
@Composable
private fun RowScope.DefaultContent(
    title: String,
    sourceLangString: String?,
    badge: @Composable () -> Unit,
) {
    SourceRowContent(title, sourceLangString, badge)
}
