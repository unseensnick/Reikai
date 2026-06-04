package yokai.presentation.details

import android.content.Context
import java.text.DecimalFormat
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * The details resume-FAB label, shared by the manga and novel screens: "Continue/Start reading
 * Chapter N", the no-number "Continue/Start reading" (for chapters with no recognized number), or
 * "All chapters read" when [chapterNumber] is null. [partiallyRead] is true when the next chapter
 * already has saved reading progress.
 */
fun detailsResumeLabel(context: Context, chapterNumber: Float?, partiallyRead: Boolean): String = when {
    chapterNumber == null -> context.getString(MR.strings.all_chapters_read)
    chapterNumber > 0 && partiallyRead ->
        context.getString(MR.strings.continue_reading_chapter_, formatChapterNumber(chapterNumber))
    chapterNumber > 0 ->
        context.getString(MR.strings.start_reading_chapter_, formatChapterNumber(chapterNumber))
    partiallyRead -> context.getString(MR.strings.continue_reading)
    else -> context.getString(MR.strings.start_reading)
}

// Main-thread only (called during composition), so a single shared formatter is safe.
private val chapterNumberFormat = DecimalFormat("#.###")

private fun formatChapterNumber(number: Float): String = chapterNumberFormat.format(number.toDouble())
