package reikai.presentation.components

import androidx.compose.ui.graphics.vector.ImageVector
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.source.model.SManga
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AttachMoney
import mihon.icons.materialsymbols.rounded.Block
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Done
import mihon.icons.materialsymbols.rounded.DoneAll
import mihon.icons.materialsymbols.rounded.Pause
import mihon.icons.materialsymbols.rounded.Schedule
import tachiyomi.i18n.MR

// NovelStatusCode reuses SManga's codes (pinned by NovelStatusCodeTest), so these serve both content types.

/** The label for a series' publishing status; a code outside the known set reads as unknown. */
fun entryStatusRes(status: Long): StringResource = when (status.toInt()) {
    SManga.ONGOING -> MR.strings.ongoing
    SManga.COMPLETED -> MR.strings.completed
    SManga.LICENSED -> MR.strings.licensed
    SManga.PUBLISHING_FINISHED -> MR.strings.publishing_finished
    SManga.CANCELLED -> MR.strings.cancelled
    SManga.ON_HIATUS -> MR.strings.on_hiatus
    else -> MR.strings.unknown
}

/** The icon drawn beside [entryStatusRes]'s label. */
fun entryStatusIcon(status: Long): ImageVector = when (status.toInt()) {
    SManga.ONGOING -> MaterialSymbols.Rounded.Schedule
    SManga.COMPLETED -> MaterialSymbols.Rounded.DoneAll
    SManga.LICENSED -> MaterialSymbols.Rounded.AttachMoney
    SManga.PUBLISHING_FINISHED -> MaterialSymbols.Rounded.Done
    SManga.CANCELLED -> MaterialSymbols.Rounded.Close
    SManga.ON_HIATUS -> MaterialSymbols.Rounded.Pause
    else -> MaterialSymbols.Rounded.Block
}
