package reikai.presentation.components

import androidx.compose.runtime.Composable
import eu.kanade.presentation.components.relativeDateText

/**
 * What a chapter with no date from the source shows. A typed slot rather than the row deciding for
 * itself, because the two types want opposite answers from the same missing value: manga says "N/A"
 * as upstream does, and a novel would say it on nearly every row, since novel sources hardly ever
 * date a chapter. Naming it here keeps that a stated divergence instead of a silent one.
 */
enum class UndatedChapterDate {
    NotApplicable,
    Blank,
}

/** A chapter row's date line, the details list's and the reader sheet's alike. */
@Composable
fun chapterRowDate(dateUpload: Long, undated: UndatedChapterDate): String? = when {
    dateUpload > 0L -> relativeDateText(dateUpload)
    // Upstream's formatter answers "N/A" for an undated chapter.
    undated == UndatedChapterDate.NotApplicable -> relativeDateText(dateUpload)
    else -> null
}
