package reikai.presentation.migrate.flow

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.InLibraryBadge
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.util.system.LocaleHelper
import reikai.presentation.browse.EntrySearchSection
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/** Cover width of one candidate in the strip. */
private val CANDIDATE_WIDTH = 96.dp

/**
 * One source's migration candidates, under the shared global-search section header.
 *
 * Both surfaces that offer a target render this: the single-entry search screen and the batch list's
 * override strip. They were separate near-identical composables, and the drift showed: long-press to
 * preview a candidate reached the deep picker but neither strip.
 *
 * Tapping a candidate picks it; long-pressing opens its details, so a match can be checked before it
 * is committed to. Tapping the header browses the whole source, which is the way out when the search
 * cannot reach the title.
 */
@Composable
internal fun MigrationCandidateStrip(
    sourceName: String,
    sourceLang: String,
    candidates: List<MigrationCandidate>,
    error: String?,
    onPick: (MigrationCandidate) -> Unit,
    onPreview: (MigrationCandidate) -> Unit,
    onBrowseSource: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    EntrySearchSection(
        title = sourceName,
        subtitle = LocaleHelper.getSourceDisplayName(sourceLang, LocalContext.current),
        onClick = onBrowseSource,
        modifier = modifier,
    ) {
        when {
            loading -> GlobalSearchLoadingResultItem()
            // A source that threw says so; "no results" would be a different, wrong answer.
            error != null -> GlobalSearchErrorResultItem(message = error)
            candidates.isEmpty() -> Text(
                text = stringResource(MR.strings.no_results_found),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            )
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
            ) {
                items(items = candidates, key = { it.key }) { candidate ->
                    Column(
                        modifier = Modifier
                            .width(CANDIDATE_WIDTH)
                            .padding(end = MaterialTheme.padding.small)
                            .combinedClickable(
                                onClick = { onPick(candidate) },
                                onLongClick = { onPreview(candidate) },
                            ),
                    ) {
                        // Dim + badge, the same pair browse uses, so an entry already in the library
                        // reads the same here as it does there. It stays pickable: migrating onto a
                        // library entry is the replace case, not a mistake.
                        Box {
                            MangaCover.Book(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(
                                        if (candidate.inLibrary) {
                                            CommonMangaItemDefaults.BrowseFavoriteCoverAlpha
                                        } else {
                                            1f
                                        },
                                    ),
                                data = candidate.cover,
                            )
                            BadgeGroup(modifier = Modifier.padding(MaterialTheme.padding.extraSmall)) {
                                InLibraryBadge(enabled = candidate.inLibrary)
                            }
                        }
                        Text(
                            text = candidate.title,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
                        )
                    }
                }
            }
        }
    }
}
