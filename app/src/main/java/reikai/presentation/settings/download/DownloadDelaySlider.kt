package reikai.presentation.settings.download

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.more.settings.widget.PrefsVerticalPadding
import eu.kanade.presentation.more.settings.widget.TitleFontSize
import reikai.domain.download.DownloadPacing
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.BaseSliderItem
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * A download delay picked by a stepped slider over [options], drawn as the settings screen's own sliders are.
 * A null option is Default, following the global delay of [defaultMs]. A stored value between stops shows at
 * the next stop up and is only rewritten once the user drags.
 */
@Composable
fun DownloadDelaySlider(
    title: String,
    selectedMs: Long?,
    options: List<Long?>,
    defaultMs: Long,
    onChange: (Long?) -> Unit,
    subtitle: String? = rateLabel(selectedMs ?: defaultMs),
    modifier: Modifier = Modifier,
    onChangeFinished: (() -> Unit)? = null,
) {
    val index = stopIndex(options, selectedMs)
    BaseSliderItem(
        value = index,
        valueRange = 0..options.lastIndex,
        steps = (options.size - 2).coerceAtLeast(0),
        title = title,
        subtitle = subtitle,
        valueString = options[index]?.let { downloadDelayLabel(it) }
            ?: stringResource(MR.strings.pref_source_delay_default, downloadDelayLabel(defaultMs)),
        onChange = { if (it != index) onChange(options[it]) },
        titleStyle = MaterialTheme.typography.titleLarge.copy(fontSize = TitleFontSize),
        modifier = modifier.padding(horizontal = PrefsHorizontalPadding, vertical = PrefsVerticalPadding),
        onChangeFinished = onChangeFinished,
    )
}

/** What a delay means in chapters a minute, or that Off sets no limit. */
@Composable
fun rateLabel(delayMs: Long): String = DownloadPacing.chaptersPerMinute(delayMs)
    ?.let { pluralStringResource(MR.plurals.pref_download_delay_rate, it, it) }
    ?: stringResource(MR.strings.pref_download_delay_back_to_back)

/** A delay as Settings shows it: "Off", "0.5 s", "2 s". */
@Composable
fun downloadDelayLabel(delayMs: Long): String {
    if (delayMs <= 0L) return stringResource(MR.strings.pref_download_delay_off)
    val seconds = if (delayMs % 1_000L == 0L) "${delayMs / 1_000L}" else "${delayMs / 1_000.0}"
    return stringResource(MR.strings.download_delay_seconds, seconds)
}

/** [selectedMs]'s stop, or the first stop above it for a value between stops, or the last. */
internal fun stopIndex(options: List<Long?>, selectedMs: Long?): Int {
    if (selectedMs == null) return options.indexOf(null).coerceAtLeast(0)
    val index = options.indexOfFirst { it != null && it >= selectedMs }
    return if (index >= 0) index else options.lastIndex
}
