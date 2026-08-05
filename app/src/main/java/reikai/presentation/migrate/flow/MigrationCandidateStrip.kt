package reikai.presentation.migrate.flow

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.tachiyomi.util.system.LocaleHelper
import reikai.presentation.browse.EntryBrowseItemUi
import reikai.presentation.browse.EntrySearchCardRow
import reikai.presentation.browse.EntrySearchSection

/**
 * One source's migration candidates, under the shared global-search section header and rendering the
 * same result cards global search does.
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
    result: StripResult,
    onPick: (MigrationCandidate) -> Unit,
    onPreview: (MigrationCandidate) -> Unit,
    onBrowseSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val candidates = result.candidates
    EntrySearchSection(
        title = sourceName,
        subtitle = LocaleHelper.getSourceDisplayName(sourceLang, LocalContext.current),
        onClick = onBrowseSource,
        modifier = modifier,
    ) {
        when (result) {
            is StripResult.Loading -> GlobalSearchLoadingResultItem()
            // A source that threw says so; "no results" would be a different, wrong answer.
            is StripResult.Failed -> GlobalSearchErrorResultItem(message = result.error)
            // The same row global search renders, so a candidate reads here exactly as it does
            // there: in-library entries dimmed and badged, and still pickable, since migrating onto
            // a library entry is the replace case rather than a mistake.
            is StripResult.Loaded -> EntrySearchCardRow(
                entries = candidates,
                key = { it.key },
                toUi = { EntryBrowseItemUi(title = it.title, cover = it.cover ?: "", favorite = it.inLibrary) },
                onClick = onPick,
                onLongClick = onPreview,
                isSelected = { false },
            )
        }
    }
}
