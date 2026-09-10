package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.presentation.reader.text.NovelWindowDiff.Step

class NovelWindowDiffTest {

    @Test
    fun `an open grows the window below the chapter before above it`() {
        NovelWindowDiff.plan(rendered = listOf(2L), wanted = listOf(1L, 2L, 3L)) shouldBe
            listOf(Step.Append(3L), Step.Prepend(1L))
    }

    @Test
    fun `chapters above arrive nearest first`() {
        NovelWindowDiff.plan(rendered = listOf(3L), wanted = listOf(1L, 2L, 3L)) shouldBe
            listOf(Step.Prepend(2L), Step.Prepend(1L))
    }

    @Test
    fun `a forward crossing drops the chapter behind before adding the one ahead`() {
        NovelWindowDiff.plan(rendered = listOf(1L, 2L, 3L), wanted = listOf(2L, 3L, 4L)) shouldBe
            listOf(Step.Evict(1L), Step.Append(4L))
    }

    @Test
    fun `a window that already matches needs nothing`() {
        NovelWindowDiff.plan(rendered = listOf(1L, 2L), wanted = listOf(1L, 2L)) shouldBe emptyList()
    }
}
