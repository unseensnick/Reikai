package reikai.domain.novel

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/** What the novel reader's bar calls the open chapter. */
enum class NovelChapterTitleFormat(val titleRes: StringResource) {
    NAME(MR.strings.name),
    NUMBER(MR.strings.novel_chapter_title_number),
    NUMBER_AND_NAME(MR.strings.novel_chapter_title_number_and_name),
}
