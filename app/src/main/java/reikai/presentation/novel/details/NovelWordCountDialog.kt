package reikai.presentation.novel.details

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import reikai.novel.content.NovelWordDensity
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.NumberFormat

/** Counting a novel's downloaded chapters, then their word count and density. Ported from Tsundoku. */
@Composable
fun NovelWordCountDialog(
    dialog: NovelDetailsDialog.WordCount,
    onDismissRequest: () -> Unit,
) {
    val toCount = dialog.chaptersToCount
    val result = dialog.result
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(
                    text = stringResource(
                        if (result == null &&
                            toCount != 0
                        ) {
                            MR.strings.action_cancel
                        } else {
                            MR.strings.action_ok
                        },
                    ),
                )
            }
        },
        title = { Text(text = stringResource(MR.strings.action_word_count)) },
        text = {
            when {
                toCount == 0 -> Text(text = stringResource(MR.strings.word_count_no_downloads))
                result == null -> CountingProgress(dialog.checkedChapters, toCount)
                result.countedChapters == 0 -> Text(text = stringResource(MR.strings.word_count_unreadable))
                else -> WordCountResult(result)
            }
        },
    )
}

/** [toCount] is null while the chapters on disk are still being listed. */
@Composable
private fun CountingProgress(checked: Int, toCount: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (toCount == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Text(text = stringResource(MR.strings.word_count_progress, checked, toCount))
            LinearProgressIndicator(progress = { checked.toFloat() / toCount }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun WordCountResult(result: NovelWordDensity) {
    val numberFormat = remember { NumberFormat.getIntegerInstance() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatRow(stringResource(MR.strings.word_count_total_words), numberFormat.format(result.totalWords))
        StatRow(stringResource(MR.strings.word_count_average_words), numberFormat.format(result.averageWords))
        StatRow(
            stringResource(MR.strings.word_count_chapters_counted),
            stringResource(
                MR.strings.word_count_of_total,
                numberFormat.format(result.countedChapters),
                numberFormat.format(result.totalChapters),
            ),
        )
        if (result.notDownloadedChapters > 0) {
            CoverageNote(
                stringResource(
                    MR.strings.word_count_not_downloaded,
                    numberFormat.format(result.notDownloadedChapters),
                    numberFormat.format(result.totalChapters),
                ),
            )
        }
        if (result.unreadableChapters > 0) {
            CoverageNote(
                stringResource(
                    MR.strings.word_count_unreadable_some,
                    numberFormat.format(result.unreadableChapters),
                    numberFormat.format(result.totalChapters),
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DensityBadge(tier = result.tier)
            Text(
                text = tierDescription(result.tier, numberFormat),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CoverageNote(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = label, modifier = Modifier.weight(1f))
        Text(text = value, fontWeight = FontWeight.SemiBold)
    }
}

/** The square 1 to [NovelWordDensity.MAX_TIER] density figure. */
@Composable
private fun DensityBadge(tier: Int) {
    val description = stringResource(MR.strings.word_density_indicator, tier)
    Box(
        modifier = Modifier
            .size(40.dp)
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = tier.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun tierDescription(tier: Int, numberFormat: NumberFormat): String {
    val maxWords = NovelWordDensity.tierMaxWords(tier)
    return if (maxWords == null) {
        stringResource(
            MR.strings.word_density_range_top,
            tier,
            NovelWordDensity.MAX_TIER,
            numberFormat.format(NovelWordDensity.tierMinWords(tier) - 1),
        )
    } else {
        stringResource(
            MR.strings.word_density_range,
            tier,
            NovelWordDensity.MAX_TIER,
            numberFormat.format(NovelWordDensity.tierMinWords(tier)),
            numberFormat.format(maxWords),
        )
    }
}
