package reikai.presentation.reader

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/** The base strings' words, standing in for the app's resources, which a unit test cannot load. */
object EnglishChapterTitleWords : ChapterTitleWords {
    override fun numbered(number: String) = "Chapter $number"

    override fun numberedWithName(number: String, name: String) = "Ch. $number: $name"

    override fun pageProgress(resource: StringResource, args: Array<Any>) = when (resource) {
        MR.strings.chapter_progress_of_total -> "Page: ${args[0]}/${args[1]}"
        else -> "Page: ${args[0]}"
    }
}
