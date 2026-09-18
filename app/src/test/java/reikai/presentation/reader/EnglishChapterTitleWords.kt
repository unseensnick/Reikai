package reikai.presentation.reader

/** The base strings' words, standing in for the app's resources, which a unit test cannot load. */
object EnglishChapterTitleWords : ChapterTitleWords {
    override fun numbered(number: String) = "Chapter $number"

    override fun numberedWithName(number: String, name: String) = "Ch. $number: $name"
}
