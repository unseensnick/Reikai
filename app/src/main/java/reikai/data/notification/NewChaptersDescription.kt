package reikai.data.notification

import android.content.Context
import eu.kanade.presentation.util.formatChapterNumber
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * What a "new chapters" notification found, decided before any string is looked up. A typed answer
 * rather than a formatted one, so the rule can be tested without resources and both content types are
 * held to it: the novel updater used to have no rule at all and could only report a count.
 */
sealed interface NewChapters {

    /** The source numbered none of them, so there is nothing to name. */
    data class Count(val total: Int) : NewChapters

    data class Single(val number: String, val remaining: Int) : NewChapters

    data class Multiple(val numbers: List<String>, val remaining: Int) : NewChapters
}

/**
 * [chapterNumbers] is every new chapter's number, unfiltered: a negative one means the source did not
 * number it, which is what [NewChapters.Count] exists for. [total] is how many arrived, so `remaining`
 * counts what the notification does not name, whether it went unnumbered or past the cap.
 */
fun newChapters(chapterNumbers: List<Double>, total: Int): NewChapters {
    val named = chapterNumbers
        .filter { it >= 0.0 }
        .sorted()
        .map(::formatChapterNumber)
        .distinct()

    return when {
        named.isEmpty() -> NewChapters.Count(total)
        named.size == 1 -> NewChapters.Single(named.first(), total - 1)
        named.size > NOTIF_MAX_CHAPTERS -> NewChapters.Multiple(
            named.take(NOTIF_MAX_CHAPTERS),
            named.size - NOTIF_MAX_CHAPTERS,
        )
        else -> NewChapters.Multiple(named, remaining = 0)
    }
}

/** The same answer written out: "Chapter 3", "Chapters 1, 2, 3 and 10 more", "5 new chapters". */
fun Context.newChaptersDescription(chapterNumbers: List<Double>, total: Int): String =
    when (val found = newChapters(chapterNumbers, total)) {
        is NewChapters.Count ->
            pluralStringResource(MR.plurals.notification_chapters_generic, found.total, found.total)
        is NewChapters.Single -> when (found.remaining) {
            0 -> stringResource(MR.strings.notification_chapters_single, found.number)
            else -> stringResource(
                MR.strings.notification_chapters_single_and_more,
                found.number,
                found.remaining,
            )
        }
        is NewChapters.Multiple -> when (found.remaining) {
            0 -> stringResource(MR.strings.notification_chapters_multiple, found.numbers.joinToString(", "))
            else -> pluralStringResource(
                MR.plurals.notification_chapters_multiple_and_more,
                found.remaining,
                found.numbers.joinToString(", "),
                found.remaining,
            )
        }
    }

/** How many chapter numbers one notification lists before it says "and N more". */
private const val NOTIF_MAX_CHAPTERS = 5

/**
 * How long a series title may be in a notification. A collapsed group draws each child as its title
 * followed by its text on one line, so an unchopped title pushes the chapters off the end and the row
 * says nothing useful.
 */
const val NOTIF_TITLE_MAX_LEN = 45
