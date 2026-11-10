package reikai.presentation.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Block
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Numbers
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.SortByAlpha
import reikai.domain.library.TagChoice
import reikai.domain.library.TagFilter
import reikai.domain.library.clearTag
import reikai.domain.library.clearTagFilter
import reikai.domain.library.currentTagFilter
import reikai.domain.library.cycleTag
import reikai.domain.library.tagChoices
import reikai.domain.library.tagKey
import reikai.domain.library.tagKeys
import tachiyomi.core.common.preference.TriState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource

/** The Filter tab's Tags row, opening the picker. Shared by both content types through [LibraryTagFilter]. */
@Composable
fun ColumnScope.TagsFilterRow(tagFilter: LibraryTagFilter) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val filter by rememberTagFilter(tagFilter)

    if (showDialog) TagFilterDialog(tagFilter, onDismiss = { showDialog = false })

    Row(
        modifier = Modifier
            .clickable { showDialog = true }
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = stringResource(MR.strings.tag_filter_title), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.weight(1f))
        if (filter.include.isNotEmpty() || filter.exclude.isNotEmpty()) {
            Text(
                text = stringResource(MR.strings.tag_filter_summary, filter.include.size, filter.exclude.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { showDialog = true }) {
            Text(stringResource(MR.strings.action_edit))
        }
    }
}

/**
 * The tag picker. A tap writes at once, cycling a tag from not filtered to shown to hidden, so the library
 * and the match count follow while it is open. A chosen tag no series carries any more stays listed with
 * a count of 0, so it can still be cleared. Only the tag list scrolls; the search and options stay put.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagFilterDialog(tagFilter: LibraryTagFilter, onDismiss: () -> Unit) {
    val preferences = tagFilter.preferences
    val filter by rememberTagFilter(tagFilter)
    // Every series' tags are folded once per opening, off the main thread, so a tap only recounts.
    val library by produceState<PickerLibrary?>(null) {
        value = withContext(Dispatchers.Default) {
            tagFilter.seriesTags().let { PickerLibrary(tagChoices(it), it.map(::tagKeys)) }
        }
    }
    val choices = library?.choices.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    var byName by rememberSaveable { mutableStateOf(false) }
    val shown = remember(choices, filter.include, filter.exclude, query, byName) {
        val stale = (filter.include + filter.exclude)
            .filter { key -> choices.none { it.key == key } }
            .map { TagChoice(it, it, 0) }
        val needle = tagKey(query)
        (stale + choices)
            .filter { needle.isEmpty() || needle in it.key }
            .let { list -> if (byName) list.sortedBy { it.label.lowercase() } else list }
    }
    val matching by produceState<Int?>(null, library, filter) {
        value = library?.let { lib -> withContext(Dispatchers.Default) { lib.keys.count(filter::matchesKeys) } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(MR.strings.tag_filter_title), modifier = Modifier.weight(1f))
                Text(
                    text = matching?.let {
                        stringResource(
                            MR.strings.tag_filter_match_count,
                            it,
                            library?.keys?.size ?: 0,
                        )
                    }
                        .orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TagSearchBar(query = query, onQueryChange = { query = it }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { byName = !byName }) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.run { if (byName) SortByAlpha else Numbers },
                            contentDescription = stringResource(
                                if (byName) MR.strings.tag_filter_sort_name else MR.strings.tag_filter_sort_count,
                            ),
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    NoTagsChip(filter.noTags) { preferences.filterNoTags.set(filter.noTags.next()) }
                    OptionChip(MR.strings.tag_filter_require_all, filter.includeAll) {
                        preferences.filterTagsIncludeAll.set(!filter.includeAll)
                    }
                    OptionChip(MR.strings.tag_filter_hide_all, filter.excludeAll) {
                        preferences.filterTagsExcludeAll.set(!filter.excludeAll)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (shown.isEmpty()) {
                        Text(
                            text = stringResource(MR.strings.tag_filter_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        shown.forEach { choice ->
                            TagChoiceChip(
                                choice = choice,
                                state = filter.stateOf(choice.key),
                                onClick = { preferences.cycleTag(choice.key) },
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = preferences::clearTagFilter) { Text(stringResource(MR.strings.action_clear)) }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_close)) }
        },
    )
}

@Composable
private fun TagSearchBar(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // A hairline outline lifts the bar off the dialog, whose surface its fill sits close to.
    val outline = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interaction,
        modifier = modifier,
        decorationBox = { field ->
            Row(
                modifier = Modifier
                    .height(44.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                    .border(1.dp, outline, CircleShape)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Box(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(MR.strings.tag_filter_search),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    field()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Close,
                            contentDescription = stringResource(MR.strings.action_clear),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun TagChoiceChip(choice: TagChoice, state: TriState, onClick: () -> Unit) {
    TriStateChip(state = state, onClick = onClick, modifier = Modifier.testTag("tag:${choice.key}")) { color ->
        Text(choice.label)
        Text(
            text = "${choice.count}",
            style = MaterialTheme.typography.labelMedium,
            color = color.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun NoTagsChip(state: TriState, onClick: () -> Unit) {
    TriStateChip(state = state, onClick = onClick) { Text(stringResource(MR.strings.tag_filter_no_tags)) }
}

@Composable
private fun OptionChip(labelRes: StringResource, selected: Boolean, onClick: () -> Unit) {
    TriStateChip(
        state = if (selected) TriState.ENABLED_IS else TriState.DISABLED,
        onClick = onClick,
    ) { Text(stringResource(labelRes)) }
}

/** A chip that is off, shown (a check), or hidden (a block sign, in the error colours). */
@Composable
private fun TriStateChip(
    state: TriState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable RowScope.(contentColor: Color) -> Unit,
) {
    val contentColor = when (state) {
        TriState.ENABLED_IS -> MaterialTheme.colorScheme.onSecondaryContainer
        TriState.ENABLED_NOT -> MaterialTheme.colorScheme.onErrorContainer
        TriState.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    FilterChip(
        selected = state != TriState.DISABLED,
        onClick = onClick,
        label = { Row(verticalAlignment = Alignment.CenterVertically) { label(contentColor) } },
        leadingIcon = when (state) {
            TriState.ENABLED_IS -> {
                { Icon(MaterialSymbols.Rounded.Check, null, Modifier.size(18.dp)) }
            }
            TriState.ENABLED_NOT -> {
                { Icon(MaterialSymbols.Rounded.Block, null, Modifier.size(18.dp)) }
            }
            TriState.DISABLED -> null
        },
        colors = if (state == TriState.ENABLED_NOT) {
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            FilterChipDefaults.filterChipColors()
        },
        modifier = modifier,
    )
}

/**
 * The active tag filter under the library's toolbar, one chip per shown or hidden tag and one for No tags.
 * A tap on a chip drops that part of the filter.
 */
@Composable
fun TagFilterStrip(tagFilter: LibraryTagFilter, modifier: Modifier = Modifier) {
    val preferences = tagFilter.preferences
    val filter by rememberTagFilter(tagFilter)
    if (!filter.isActive) return
    // The stored keys are folded, so the chips show the spelling the library uses where one is found.
    val labels by produceState(emptyMap<String, String>(), filter.include, filter.exclude) {
        value = withContext(Dispatchers.Default) { tagChoices(tagFilter.seriesTags()).associate { it.key to it.label } }
    }

    // Before the library loads no spelling is known yet, so the folded key stands in, capitalised.
    fun labelOf(key: String) = labels[key] ?: key.replaceFirstChar(Char::uppercaseChar)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filter.include.sorted().forEach { key ->
            StripChip(labelOf(key)) { preferences.clearTag(key) }
        }
        filter.exclude.sorted().forEach { key ->
            StripChip(stringResource(MR.strings.tag_filter_chip_hidden, labelOf(key))) {
                preferences.clearTag(key)
            }
        }
        if (filter.noTags != TriState.DISABLED) {
            val label = if (filter.noTags == TriState.ENABLED_IS) {
                MR.strings.tag_filter_chip_untagged
            } else {
                MR.strings.tag_filter_chip_tagged
            }
            StripChip(stringResource(label)) { preferences.filterNoTags.set(TriState.DISABLED) }
        }
    }
}

@Composable
private fun StripChip(label: String, onClear: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onClear,
        label = { Text(label) },
        trailingIcon = { Icon(MaterialSymbols.Rounded.Close, null, Modifier.size(18.dp)) },
    )
}

@Composable
private fun rememberTagFilter(tagFilter: LibraryTagFilter): State<TagFilter> =
    tagFilter.state.collectAsState(initial = remember { tagFilter.preferences.currentTagFilter() })

private class PickerLibrary(val choices: List<TagChoice>, val keys: List<Set<String>>)

private fun TagFilter.stateOf(key: String) = when (key) {
    in include -> TriState.ENABLED_IS
    in exclude -> TriState.ENABLED_NOT
    else -> TriState.DISABLED
}
