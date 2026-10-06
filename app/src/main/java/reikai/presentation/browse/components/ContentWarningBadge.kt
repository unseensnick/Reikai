package reikai.presentation.browse.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.browse.components.ExtensionPill
import eu.kanade.presentation.browse.components.label
import mihon.domain.extension.model.ContentWarning
import tachiyomi.presentation.core.i18n.stringResource

/** A source's content warning beside its name, the pill its extension's row draws. Nothing when safe. */
@Composable
fun ContentWarningBadge(
    contentWarning: ContentWarning,
    modifier: Modifier = Modifier,
) {
    val label = contentWarning.label ?: return
    ExtensionPill(text = stringResource(label.title), modifier = modifier, color = label.color)
}
