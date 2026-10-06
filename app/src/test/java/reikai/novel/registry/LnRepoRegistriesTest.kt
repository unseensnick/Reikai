package reikai.novel.registry

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore

/** Each added repo is downloaded once per reason to, and keeps its own outcome. */
class LnRepoRegistriesTest {

    private val prefs = NovelPreferences(EmittingPreferenceStore())
    private val fetched = mutableListOf<String>()
    private val fetcher = LnRegistryFetcher { repo ->
        fetched += repo
        if (repo == DOWN_REPO) error("HTTP 404") else listOf(ENTRY)
    }
    private val registries = LnRepoRegistries(fetcher, prefs)

    @Test
    fun `reading the registries a second time downloads nothing more`() = runTest {
        prefs.addedRepoUrls().set(setOf(REPO))

        registries.results.first()
        registries.results.first()

        fetched shouldBe listOf(REPO)
    }

    @Test
    fun `adding a repo downloads only the new one`() = runTest {
        prefs.addedRepoUrls().set(setOf(REPO))
        registries.results.first()

        prefs.addedRepoUrls().set(setOf(REPO, OTHER_REPO))
        registries.results.first()

        fetched shouldBe listOf(REPO, OTHER_REPO)
    }

    @Test
    fun `removing a repo downloads nothing and drops it`() = runTest {
        prefs.addedRepoUrls().set(setOf(REPO, OTHER_REPO))
        registries.results.first()
        fetched.clear()

        prefs.addedRepoUrls().set(setOf(REPO))

        (registries.results.first().keys to fetched) shouldBe (setOf(REPO) to emptyList<String>())
    }

    @Test
    fun `an unreachable repo is reported apart from the ones that answered`() = runTest {
        prefs.addedRepoUrls().set(setOf(REPO, DOWN_REPO))

        registries.results.first() shouldBe mapOf(
            REPO to LnRepoResult.Reached(listOf(ENTRY)),
            DOWN_REPO to LnRepoResult.Unreachable("HTTP 404"),
        )
    }

    @Test
    fun `fetching every repo reports an unreachable one beside the ones that answered`() = runTest {
        fetcher.fetchEach(listOf(REPO, DOWN_REPO)) shouldBe mapOf(
            REPO to LnRepoResult.Reached(listOf(ENTRY)),
            DOWN_REPO to LnRepoResult.Unreachable("HTTP 404"),
        )
    }

    @Test
    fun `a refresh downloads every repo again`() = runTest {
        prefs.addedRepoUrls().set(setOf(REPO, OTHER_REPO))
        registries.results.first()
        fetched.clear()

        registries.refresh()

        fetched.toSet() shouldBe setOf(REPO, OTHER_REPO)
    }

    @Test
    fun `an added repo is downloaded once, by the add`() = runTest {
        registries.results.first()

        registries.add(REPO)
        registries.results.first()

        fetched shouldBe listOf(REPO)
    }

    @Test
    fun `an address that does not read as a registry is not added`() = runTest {
        registries.add(DOWN_REPO)

        prefs.addedRepoUrls().get() shouldBe emptySet()
    }

    @Test
    fun `a plugin two repos list belongs to the earlier one`() {
        val listing = LnRepoResult.Reached(listOf(ENTRY))

        pluginRepos(mapOf(REPO to listing, OTHER_REPO to listing)) shouldBe mapOf(ENTRY.url to REPO)
    }

    @Test
    fun `a listed script with brackets is found under its canonical url`() {
        val bracketed = ENTRY.copy(url = "https://example.org/NovelBin[readnovelfull].js")

        pluginRepos(mapOf(REPO to LnRepoResult.Reached(listOf(bracketed)))) shouldBe
            mapOf("https://example.org/NovelBin%5Breadnovelfull%5D.js" to REPO)
    }

    @Test
    fun `a repo that did not answer owns no plugin`() {
        pluginRepos(mapOf(DOWN_REPO to LnRepoResult.Unreachable("HTTP 404"))) shouldBe emptyMap()
    }

    private companion object {
        const val REPO = "https://example.org/plugins.json"
        const val OTHER_REPO = "https://example.net/plugins.json"
        const val DOWN_REPO = "https://example.com/plugins.json"
        val ENTRY = LnRegistryEntry(
            id = "novelbin",
            name = "NovelBin",
            version = "1.0.0",
            site = "https://novelbin.example",
            lang = "English",
            url = "https://example.org/novelbin.js",
        )
    }
}
