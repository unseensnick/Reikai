package reikai.presentation.reader.text

/**
 * The steps that turn the window a renderer holds into the one the model wants, in the order they
 * must run. Dropped chapters go first, then the ones after the chapter being read, then the ones
 * before it: a chapter added above keeps the reader's place only while there is room below to scroll
 * into, and a chapter shorter than the screen has none until the one after it has arrived.
 */
object NovelWindowDiff {

    sealed interface Step {
        val chapterId: Long

        data class Evict(override val chapterId: Long) : Step
        data class Append(override val chapterId: Long) : Step
        data class Prepend(override val chapterId: Long) : Step
    }

    /** Prepends come nearest-first, since each goes on top of the last. */
    fun plan(rendered: List<Long>, wanted: List<Long>): List<Step> =
        rendered.filterNot { it in wanted }.map(Step::Evict) +
            wanted.takeLastWhile { it !in rendered }.map(Step::Append) +
            wanted.takeWhile { it !in rendered }.reversed().map(Step::Prepend)
}
