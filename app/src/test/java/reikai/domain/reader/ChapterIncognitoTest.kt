package reikai.domain.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.source.SourceKey

/**
 * The rule both readers write through: the source of the entry that owns a chapter decides whether
 * writing it stays private, so one session over a merged series can hold both answers.
 */
class ChapterIncognitoTest {

    /** Owner 1 is on [private], owner 2 on [public], owner 3 on [private] as well. */
    class Sources(private val name: String, val private: SourceKey, val public: SourceKey) {
        override fun toString() = name

        val ownerSource = mapOf(1L to private, 2L to public, 3L to private)
    }

    private fun incognito(sources: Sources, asked: MutableList<SourceKey?> = mutableListOf()) = ChapterIncognito(
        sourceOf = { sources.ownerSource[it] },
        isIncognito = { source ->
            asked += source
            source == sources.private
        },
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    fun `each chapter is private by its own owner's source`(sources: Sources) = runTest {
        val incognito = incognito(sources)

        listOf(incognito.of(1L), incognito.of(2L)) shouldBe listOf(true, false)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    fun `a source is asked once however many of its chapters are written`(sources: Sources) = runTest {
        val asked = mutableListOf<SourceKey?>()
        val incognito = incognito(sources, asked)

        incognito.of(1L)
        incognito.of(3L)
        incognito.of(1L)

        asked shouldBe listOf(sources.private)
    }

    companion object {
        @JvmStatic
        fun sources() = listOf(
            Sources("manga", SourceKey.Manga(10L), SourceKey.Manga(20L)),
            Sources("novel", SourceKey.Novel("alpha"), SourceKey.Novel("beta")),
        )
    }
}
