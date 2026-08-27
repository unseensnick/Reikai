package reikai.presentation.browse.migrate

import eu.kanade.domain.source.interactor.SetMigrateSorting
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.source.SourceKey

/**
 * The migrate list is ordered once for both content types, so these pin what a user sees in it: what
 * leads, what the two modes order by, and that the direction toggle reverses all of it.
 */
class CompareMigrateRowsTest {

    @Test
    fun `sources that are gone lead, whatever they are named`() {
        // They hold entries nothing can open, which is what the screen exists for.
        sorted(
            manga("Alpha", count = 9),
            novel("Zed", count = 1, isStub = true),
        ) shouldBe listOf("Zed", "Alpha")
    }

    @Test
    fun `sources that are gone still lead when sorting by count`() {
        // The gone one holds the most, so counting alone would put it last.
        sorted(
            manga("Alpha", count = 1),
            novel("Zed", count = 9, isStub = true),
            mode = SetMigrateSorting.Mode.TOTAL,
        ) shouldBe listOf("Zed", "Alpha")
    }

    @Test
    fun `a manga source and a novel source order together by name`() {
        sorted(
            novel("Charlie", count = 1),
            manga("alpha", count = 5),
            novel("Bravo", count = 3),
        ) shouldBe listOf("alpha", "Bravo", "Charlie")
    }

    @Test
    fun `by count, across both content types`() {
        sorted(
            novel("Charlie", count = 1),
            manga("Alpha", count = 5),
            novel("Bravo", count = 3),
            mode = SetMigrateSorting.Mode.TOTAL,
        ) shouldBe listOf("Charlie", "Bravo", "Alpha")
    }

    @Test
    fun `the direction toggle reverses the whole order, gone sources included`() {
        sorted(
            manga("Alpha", count = 9),
            novel("Zed", count = 1, isStub = true),
            direction = SetMigrateSorting.Direction.DESCENDING,
        ) shouldBe listOf("Alpha", "Zed")
    }

    private fun sorted(
        vararg rows: BrowseMigrateRow,
        mode: SetMigrateSorting.Mode = SetMigrateSorting.Mode.ALPHABETICAL,
        direction: SetMigrateSorting.Direction = SetMigrateSorting.Direction.ASCENDING,
    ) = rows.sortedWith(compareMigrateRows(mode, direction)).map { it.name }

    private var nextId = 0L

    private fun manga(name: String, count: Long, isStub: Boolean = false) =
        BrowseMigrateRow(SourceKey.Manga(nextId++), name, lang = "en", count, isStub, source = Unit)

    private fun novel(name: String, count: Long, isStub: Boolean = false) =
        BrowseMigrateRow(SourceKey.Novel(name), name, lang = "en", count, isStub, source = Unit)
}
