package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.eHentaiSourceIds
import exh.util.SourceTagsUtil

/**
 * Namespaced tag chips for adult/metadata galleries on the details screen. Ported from Komikku's
 * SearchMetadataChips/NamespaceTags, reimplemented on Mihon's Material3 SuggestionChip (Komikku's
 * version pulls in its own chip components). Groups a gallery's tags by namespace, border-weights
 * E-Hentai tags by tag type, and builds each chip's tap-search query via SourceTagsUtil. Tapping a
 * chip drives the caller's shared search/copy menu, same as the flat genre chips.
 */
@Immutable
data class DisplayTag(
    val namespace: String?,
    val text: String,
    val search: String,
    val border: Int?,
)

@Immutable
@JvmInline
value class SearchMetadataChips(
    val tags: Map<String, List<DisplayTag>>,
) {
    companion object {
        operator fun invoke(
            meta: RaisedSearchMetadata?,
            sourceId: Long,
            genre: List<String>?,
        ): SearchMetadataChips? {
            if (meta != null) {
                val grouped = meta.tags
                    .filterNot { it.type == RaisedSearchMetadata.TAG_TYPE_VIRTUAL }
                    .map { tag ->
                        DisplayTag(
                            namespace = tag.namespace,
                            text = tag.name,
                            search = if (!tag.namespace.isNullOrEmpty()) {
                                SourceTagsUtil.getWrappedTag(sourceId, namespace = tag.namespace, tag = tag.name)
                            } else {
                                SourceTagsUtil.getWrappedTag(sourceId, fullTag = tag.name)
                            } ?: tag.name,
                            border = if (sourceId in eHentaiSourceIds) {
                                when (tag.type) {
                                    EHentaiSearchMetadata.TAG_TYPE_NORMAL -> 2
                                    EHentaiSearchMetadata.TAG_TYPE_LIGHT -> 1
                                    else -> null
                                }
                            } else {
                                null
                            },
                        )
                    }
                    .groupBy { it.namespace.orEmpty() }
                return if (grouped.isEmpty()) null else SearchMetadataChips(grouped)
            }
            // Before the metadata object is loaded, fall back to the namespaced genre strings
            // ("namespace: tag") so the grouped chips render from the first frame instead of flashing
            // the flat view (matches Komikku). Every adult/metadata source stores genre this way via
            // RaisedSearchMetadata.tagsToGenreString, and the all-colon check keeps normal manga flat.
            if (!genre.isNullOrEmpty() && genre.all { it.contains(':') }) {
                val grouped = genre.map { raw ->
                    val index = raw.indexOf(':')
                    val namespace = raw.substring(0, index).trim()
                    val name = raw.substring(index + 1).trim()
                    DisplayTag(
                        namespace = namespace,
                        text = name,
                        search = SourceTagsUtil.getWrappedTag(sourceId, namespace = namespace, tag = name) ?: raw,
                        border = null,
                    )
                }.groupBy { it.namespace.orEmpty() }
                return SearchMetadataChips(grouped)
            }
            return null
        }
    }
}

@Composable
fun NamespaceTags(
    tags: SearchMetadataChips,
    onClick: (search: String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.tags.forEach { (namespace, group) ->
            Row(Modifier.padding(start = 16.dp)) {
                if (namespace.isNotEmpty()) {
                    Text(
                        text = namespace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                FlowRow(
                    modifier = Modifier.padding(start = 8.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    group.forEach { tag ->
                        MetadataTagChip(
                            modifier = Modifier.padding(vertical = 4.dp),
                            text = tag.text,
                            borderWidth = tag.border?.dp,
                            onClick = { onClick(tag.search) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataTagChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    borderWidth: Dp? = null,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        SuggestionChip(
            modifier = modifier,
            onClick = onClick,
            label = {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            border = borderWidth?.let {
                SuggestionChipDefaults.suggestionChipBorder(
                    enabled = true,
                    borderWidth = it,
                    borderColor = MaterialTheme.colorScheme.primary,
                )
            } ?: SuggestionChipDefaults.suggestionChipBorder(enabled = true),
        )
    }
}
