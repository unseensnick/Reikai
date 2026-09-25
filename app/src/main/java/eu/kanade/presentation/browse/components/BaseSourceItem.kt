package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.domain.source.model.Source
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.util.secondaryItemAlpha

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
    SourceIcon(source = source)
}

// RK: was a val, now a function so the badge slot and title above can reach the name row.
@Composable
private fun RowScope.DefaultContent(
    title: String,
    sourceLangString: String?,
    badge: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = MaterialTheme.padding.medium)
            .weight(1f),
    ) {
        // RK --> the name row also holds the content-type badge
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            badge()
        }
        // RK <--
        if (sourceLangString != null) {
            Text(
                modifier = Modifier.secondaryItemAlpha(),
                text = sourceLangString,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
