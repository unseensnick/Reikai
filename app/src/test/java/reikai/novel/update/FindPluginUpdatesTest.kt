package reikai.novel.update

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.novel.registry.LnRegistryEntry

class FindPluginUpdatesTest {

    private val installedUrl = "https://repo.test/v1/novelbin.js"
    private val metadata = mapOf(installedUrl to LnInstalledPluginMetadata(pluginId = "novelbin", version = "1.0.0"))

    /** Some repos publish each version at its own URL, so the URL alone never finds the newer one. */
    @Test
    fun `a newer version at a new URL is an update of the installed plugin`() {
        val newer = entry(version = "1.1.0", url = "https://repo.test/v2/novelbin.js")

        findPluginUpdates(setOf(installedUrl), metadata, listOf(newer)).map { it.entry } shouldContainExactly
            listOf(newer)
    }

    @Test
    fun `the version already installed is not an update`() {
        val same = entry(version = "1.0.0", url = installedUrl)

        findPluginUpdates(setOf(installedUrl), metadata, listOf(same)).shouldBeEmpty()
    }

    private fun entry(version: String, url: String) = LnRegistryEntry(
        id = "novelbin",
        name = "NovelBin",
        version = version,
        site = "https://novelbin.test",
        lang = "English",
        url = url,
    )
}
