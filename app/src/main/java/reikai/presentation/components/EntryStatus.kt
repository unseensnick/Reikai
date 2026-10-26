package reikai.presentation.components

import androidx.compose.ui.graphics.vector.ImageVector
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.source.model.SManga
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AttachMoney
import mihon.icons.materialsymbols.rounded.Block
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.DoneAll
import mihon.icons.materialsymbols.rounded.Schedule
import mihon.icons.materialsymbols.roundedfilled.Pause
import tachiyomi.i18n.MR

/**
 * A series' publishing status as every surface shows and searches it, one row per code, so a new code is
 * added once. NovelStatusCode reuses SManga's codes (pinned by NovelStatusCodeTest), so it serves both
 * content types; a code outside the known set reads as [UNKNOWN].
 */
enum class EntryStatus(
    private val code: Int,
    val label: StringResource,
    val icon: () -> ImageVector,
    /** The word `status:` matches it by. */
    val queryWord: String,
) {
    ONGOING(SManga.ONGOING, MR.strings.ongoing, { MaterialSymbols.Rounded.Schedule }, "ongoing"),
    COMPLETED(SManga.COMPLETED, MR.strings.completed, { MaterialSymbols.Rounded.DoneAll }, "completed"),
    LICENSED(SManga.LICENSED, MR.strings.licensed, { MaterialSymbols.Rounded.AttachMoney }, "licensed"),
    PUBLISHING_FINISHED(
        SManga.PUBLISHING_FINISHED,
        MR.strings.publishing_finished,
        { MaterialSymbols.Rounded.Check },
        "finished",
    ),
    CANCELLED(SManga.CANCELLED, MR.strings.cancelled, { MaterialSymbols.Rounded.Close }, "cancelled"),
    ON_HIATUS(SManga.ON_HIATUS, MR.strings.on_hiatus, { MaterialSymbols.RoundedFilled.Pause }, "hiatus"),
    UNKNOWN(SManga.UNKNOWN, MR.strings.unknown, { MaterialSymbols.Rounded.Block }, "unknown"),
    ;

    companion object {
        fun of(code: Long): EntryStatus = entries.firstOrNull { it.code.toLong() == code } ?: UNKNOWN
    }
}

/** The label for a series' publishing status. */
fun entryStatusRes(status: Long): StringResource = EntryStatus.of(status).label

/** The icon drawn beside [entryStatusRes]'s label. */
fun entryStatusIcon(status: Long): ImageVector = EntryStatus.of(status).icon()
