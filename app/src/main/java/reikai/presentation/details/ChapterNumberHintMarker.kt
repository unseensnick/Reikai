package reikai.presentation.details

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.Warning
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.IconButtonTokens
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The marker on a chapter row whose number looks out of line, one title line ([lineHeight] px) square.
 * Its tap box is upstream's in-row 40dp, laid out at the marker's size so the row is no taller: the box
 * overhangs into the row's padding, where a near miss would otherwise open the chapter.
 */
@Composable
fun ChapterNumberHintMarker(lineHeight: Int, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .layout { measurable, _ ->
                val box = IconButtonTokens.StateLayerSize.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(box, box))
                layout(lineHeight, lineHeight) { placeable.place((lineHeight - box) / 2, (lineHeight - box) / 2) }
            }
            .combinedClickable(onClick = onClick, onLongClick = onClick),
    ) {
        Icon(
            imageVector = MaterialSymbols.RoundedFilled.Warning,
            contentDescription = stringResource(MR.strings.chapter_number_hint),
            modifier = Modifier.size(with(LocalDensity.current) { lineHeight.toDp() }),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}
