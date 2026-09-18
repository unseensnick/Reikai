package reikai.novel.update

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.novel.registry.LnRegistryEntry

class FindPluginUpdatesTest {

    private val installedUrl = "https://repo.test/v1/novelbin.js"
    private val otherRepoUrl = "https://other.test/novelbin.js"
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

    @Test
    fun `a newer version at the installed URL wins over an older one listed first`() {
        val newer = entry(version = "1.2.0", url = installedUrl)
        val entries = listOf(entry(version = "0.9.0", url = otherRepoUrl), newer)

        findPluginUpdates(setOf(installedUrl), metadata, entries).map { it.entry } shouldContainExactly
            listOf(newer)
    }

    @Test
    fun `an update is not taken from another repo when the installed URL has a newer one`() {
        val newer = entry(version = "1.2.0", url = installedUrl)
        val entries = listOf(entry(version = "1.1.5", url = otherRepoUrl), newer)

        findPluginUpdates(setOf(installedUrl), metadata, entries).map { it.entry } shouldContainExactly
            listOf(newer)
    }

    /** Accepting another repo's entry replaces the installed script with that repo's. */
    @Test
    fun `a listing at the installed URL keeps another repo from moving the plugin`() {
        val entries = listOf(entry(version = "1.3.0", url = otherRepoUrl), entry(version = "1.0.0", url = installedUrl))

        findPluginUpdates(setOf(installedUrl), metadata, entries).shouldBeEmpty()
    }

    @Test
    fun `with the installed URL no longer listed the highest version wins`() {
        val newest = entry(version = "1.2.0", url = "https://repo.test/v2/novelbin.js")
        val entries = listOf(entry(version = "1.1.0", url = otherRepoUrl), newest)

        findPluginUpdates(setOf(installedUrl), metadata, entries).map { it.entry } shouldContainExactly
            listOf(newest)
    }

    @Test
    fun `between equal versions elsewhere the earlier repo wins`() {
        val first = entry(version = "1.2.0", url = otherRepoUrl)
        val entries = listOf(first, entry(version = "1.2.0", url = "https://repo.test/v2/novelbin.js"))

        findPluginUpdates(setOf(installedUrl), metadata, entries).map { it.entry } shouldContainExactly
            listOf(first)
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
