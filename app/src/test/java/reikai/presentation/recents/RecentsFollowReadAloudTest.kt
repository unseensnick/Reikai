package reikai.presentation.recents

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId

/** Only a resume follows read aloud; an updated chapter is a chapter of its own. */
class RecentsFollowReadAloudTest {

    @ParameterizedTest(name = "{0}, active {1}")
    @MethodSource("lanes")
    fun `a tap follows read aloud only from the read lane while it is active`(
        lane: RecentsLane,
        active: Boolean,
        follows: Boolean,
    ) {
        lane.followsReadAloud(active) shouldBe follows
    }

    companion object {
        private val chapter = ChapterRef(EntryId.Novel(1L), 5L)

        @JvmStatic
        fun lanes() = listOf(
            Arguments.of(RecentsLane.Read(chapter), true, true),
            Arguments.of(RecentsLane.Read(chapter), false, false),
            Arguments.of(RecentsLane.Updated(chapter), true, false),
        )
    }
}
