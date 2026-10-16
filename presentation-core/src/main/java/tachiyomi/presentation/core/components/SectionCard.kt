package tachiyomi.presentation.core.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.icerock.moko.resources.StringResource
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

// RK: no LazyItemScope receiver, a modifier on the card and an arrangement for a card stretched taller than its
// content, so Statistics can set these cards in rows of one height
@Composable
fun SectionCard(
    titleRes: StringResource? = null,
    modifier: Modifier = Modifier, // RK
    verticalArrangement: Arrangement.Vertical = Arrangement.Top, // RK
    content: @Composable ColumnScope.() -> Unit,
) {
    if (titleRes != null) {
        Text(
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.extraLarge),
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
        )
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier) // RK
            .padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        // RK --> arranged content is measured at its own height first, since a child that fills the height
        // (BaseStatsItem) would otherwise take the whole stretched card
        if (verticalArrangement == Arrangement.Top) {
            Column(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
                content()
            }
        } else {
            Column(
                modifier = Modifier.fillMaxHeight().padding(MaterialTheme.padding.medium),
                verticalArrangement = verticalArrangement,
            ) {
                Column(modifier = Modifier.height(IntrinsicSize.Min), content = content)
            }
        }
        // RK <--
    }
}
