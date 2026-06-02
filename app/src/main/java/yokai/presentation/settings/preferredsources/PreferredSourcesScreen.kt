package yokai.presentation.settings.preferredsources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import yokai.i18n.MR
import yokai.presentation.AppBarType
import yokai.presentation.YokaiScaffold
import yokai.presentation.settings.preferredsources.PreferredSourcesScreenModel.SourceItem
import yokai.util.Screen

/**
 * Global preferred-source ranking screen (Phase 6b). Top section is the ordered ranking (tap-to-swap
 * arrows, like [yokai.presentation.library.settings.FilterReorderList]); bottom section adds any
 * remaining installed source. Pure render over [PreferredSourcesScreenModel] state.
 */
class PreferredSourcesScreen : Screen() {

    @Composable
    override fun Content() {
        val onBackPress = LocalBackPress.currentOrThrow
        val screenModel = rememberScreenModel { PreferredSourcesScreenModel() }
        val state by screenModel.state.collectAsState()

        YokaiScaffold(
            onNavigationIconClicked = onBackPress,
            title = stringResource(MR.strings.pref_preferred_sources),
            appBarType = AppBarType.SMALL,
        ) { innerPadding ->
            val success = state as? PreferredSourcesScreenModel.State.Success ?: return@YokaiScaffold
            LazyColumn(modifier = Modifier.padding(innerPadding).fillMaxWidth()) {
                item {
                    Text(
                        text = stringResource(MR.strings.pref_preferred_sources_guide),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }

                if (success.preferred.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(MR.strings.pref_preferred_sources_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    itemsIndexed(success.preferred) { index, item ->
                        PreferredRow(
                            item = item,
                            canMoveUp = index > 0,
                            canMoveDown = index < success.preferred.lastIndex,
                            onMoveUp = { screenModel.moveUp(item.id) },
                            onMoveDown = { screenModel.moveDown(item.id) },
                            onRemove = { screenModel.removeSource(item.id) },
                        )
                    }
                }

                if (success.available.isNotEmpty()) {
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        SectionHeader(stringResource(MR.strings.pref_preferred_sources_add))
                    }
                    items(success.available) { item ->
                        AvailableRow(item = item, onAdd = { screenModel.addSource(item.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun PreferredRow(
    item: SourceItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    SourceRow(item) {
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null)
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.strings.remove))
        }
    }
}

@Composable
private fun AvailableRow(item: SourceItem, onAdd: () -> Unit) {
    SourceRow(item, modifier = Modifier.clickable(onClick = onAdd)) {
        IconButton(onClick = onAdd) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(MR.strings.add))
        }
    }
}

@Composable
private fun SourceRow(
    item: SourceItem,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.lang.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing()
    }
}
