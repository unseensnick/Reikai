package reikai.presentation.browse.migrate

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
}
