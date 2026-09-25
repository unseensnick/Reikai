package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The source ladder both the config screen and the search layer read, so what the screen showed is
 * what gets searched: saved order, else pinned, else every enabled source.
 */
class SelectMigrationSourcesTest {

    private val enabled = listOf("a", "b", "c").map {
        MigrationSourceUi(it, it.uppercase(), "en", MigrationSourceIcon.NovelUrl(null))
    }

    private fun select(saved: List<String> = emptyList(), pinned: Set<String> = emptySet()) =
        selectMigrationSources(enabled, saved, pinned).map { it.key }

    @Test
    fun `with nothing saved and nothing pinned every enabled source is selected`() {
        select() shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `pinned sources lead when nothing is saved`() {
        select(pinned = setOf("b")) shouldBe listOf("b")
    }

    @Test
    fun `a saved selection wins over the pinned sources, in its saved order`() {
        select(saved = listOf("c", "a"), pinned = setOf("b")) shouldBe listOf("c", "a")
    }

    @Test
    fun `a saved key that is no longer enabled falls through to the next tier`() {
        select(saved = listOf("gone"), pinned = setOf("b")) shouldBe listOf("b")
    }
}
