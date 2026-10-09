package reikai.presentation.browse.components

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.Warning

/**
 * The sign that an entry's source is no longer installed, for manga and novels alike: the warning
 * SourceIcon draws for a manga stub. Callers size it; the library badge wraps the same icon its own way.
 */
@Composable
fun MissingSourceIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = MaterialSymbols.RoundedFilled.Warning,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}
