package reikai.domain.reader

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/** What the reader's bar calls the open chapter. Each reader stores its own choice. */
enum class ChapterTitleFormat(val titleRes: StringResource) {
    NAME(MR.strings.name),
    NUMBER(MR.strings.chapter_title_number),
    NUMBER_AND_NAME(MR.strings.chapter_title_number_and_name),
}
