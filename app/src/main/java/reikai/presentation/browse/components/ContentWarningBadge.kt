package reikai.presentation.browse.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.browse.components.label
import mihon.domain.extension.model.ContentWarning
import tachiyomi.presentation.core.i18n.stringResource

/** A source's content warning beside its name, labelled as its extension's row labels it. Nothing when safe. */
@Composable
fun ContentWarningBadge(
    contentWarning: ContentWarning,
    modifier: Modifier = Modifier,
) {
    val label = contentWarning.label ?: return
    Text(
        text = stringResource(label.title).uppercase(),
        modifier = modifier,
        color = label.color,
        maxLines = 1,
        style = MaterialTheme.typography.labelSmall,
    )
}
