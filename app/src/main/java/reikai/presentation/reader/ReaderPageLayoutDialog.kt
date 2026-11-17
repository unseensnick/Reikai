package reikai.presentation.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import reikai.domain.novel.NovelPageLayout
import tachiyomi.i18n.MR

/**
 * The novel bar's reading-mode picker, writing the novel's own mode as the sheet's For this series row
 * does. Manga's `ReadingModeSelectDialog` goes through the same [ReaderModeDialog].
 */
@Composable
fun ReaderPageLayoutDialog(
    settings: ReaderTextSettings,
    onChange: (NovelPageLayout?) -> Unit,
    onDismiss: () -> Unit,
) {
    val own by settings.pageLayout.collectAsState(null)
    val resolved by settings.effectivePageLayout.collectAsState()
    ReaderModeDialog(
        title = MR.strings.pref_category_reading_mode,
        choices = NovelPageLayout.entries,
        stored = own,
        resolved = resolved,
        icon = { it.barIcon.icon },
        label = { it.titleRes },
        onChange = onChange,
        onDismiss = onDismiss,
    )
}

/** The manga mode whose icon draws a novel's [NovelPageLayout] on the bar and in its picker; null is Default.
 *  The bar takes upstream's [ReadingMode] for its icon, so the two readers' buttons draw the same images. */
val NovelPageLayout?.barIcon: ReadingMode
    get() = when (this) {
        null -> ReadingMode.DEFAULT
        NovelPageLayout.SCROLL -> ReadingMode.WEBTOON
        NovelPageLayout.PAGED_LTR -> ReadingMode.LEFT_TO_RIGHT
        NovelPageLayout.PAGED_RTL -> ReadingMode.RIGHT_TO_LEFT
    }
