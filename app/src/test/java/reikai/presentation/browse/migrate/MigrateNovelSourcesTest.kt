package reikai.presentation.browse.migrate

import eu.kanade.domain.source.interactor.SetMigrateSorting
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnSourceIdentity

class MigrateNovelSourcesTest {

    private fun id(name: String) = LnSourceIdentity(name = name)

    @Test
    fun `counts favorited novels per source`() {
        val rows = buildNovelMigrateSources(
            sourceIdsPerNovel = listOf("a", "a", "b"),
            installed = mapOf("a" to id("Alpha"), "b" to id("Bravo")),
            cached = emptyMap(),
        )
        rows.associate { it.id to it.count } shouldBe mapOf("a" to 2, "b" to 1)
    }

    @Test
    fun `resolves name and icon from the installed source`() {
        val rows = buildNovelMigrateSources(
            sourceIdsPerNovel = listOf("a"),
            installed = mapOf("a" to LnSourceIdentity(name = "Alpha", iconUrl = "http://i/a.png")),
            cached = mapOf("a" to id("Stale")),
        )
        rows.single().let { it.name to it.iconUrl } shouldBe ("Alpha" to "http://i/a.png")
    }

    @Test
    fun `falls back to the last-known cache when the plugin is uninstalled`() {
        val rows = buildNovelMigrateSources(
            sourceIdsPerNovel = listOf("a"),
            installed = emptyMap(),
            cached = mapOf("a" to LnSourceIdentity(name = "Alpha", iconUrl = "http://i/a.png")),
        )
        rows.single().let { Triple(it.name, it.iconUrl, it.isInstalled) } shouldBe
            Triple("Alpha", "http://i/a.png", false)
    }

    @Test
    fun `falls back to the raw plugin id when never seen`() {
        val rows = buildNovelMigrateSources(
            sourceIdsPerNovel = listOf("novelbin"),
            installed = emptyMap(),
            cached = emptyMap(),
        )
        rows.single().let { Triple(it.name, it.iconUrl, it.isInstalled) } shouldBe
            Triple("novelbin", null, false)
    }

    @Test
    fun `marks installed only when the source is currently registered`() {
        val rows = buildNovelMigrateSources(
            sourceIdsPerNovel = listOf("a", "b"),
            installed = mapOf("a" to id("Alpha")),
            cached = mapOf("b" to id("Bravo")),
        )
        rows.associate { it.id to it.isInstalled } shouldBe mapOf("a" to true, "b" to false)
    }

    @Test
    fun `sorts alphabetically ascending by name`() {
        val rows = listOf(row("c", "Charlie", 1), row("a", "alpha", 5), row("b", "Bravo", 3))
        val sorted = sortNovelMigrateSources(
            rows,
            SetMigrateSorting.Mode.ALPHABETICAL,
            SetMigrateSorting.Direction.ASCENDING,
        )
        sorted.map { it.id } shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `sorts by total count descending`() {
        val rows = listOf(row("c", "Charlie", 1), row("a", "Alpha", 5), row("b", "Bravo", 3))
        val sorted = sortNovelMigrateSources(
            rows,
            SetMigrateSorting.Mode.TOTAL,
            SetMigrateSorting.Direction.DESCENDING,
        )
        sorted.map { it.id } shouldBe listOf("a", "b", "c")
    }

    private fun row(id: String, name: String, count: Int) =
        NovelMigrateSource(id = id, name = name, iconUrl = null, lang = "", count = count, isInstalled = true)
}
