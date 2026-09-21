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
    fun `a refresh downloads every repo again`() = runTest {
        prefs.addedRepoUrls().set(setOf(REPO, OTHER_REPO))
        registries.results.first()
        fetched.clear()

        registries.refresh()

        fetched.toSet() shouldBe setOf(REPO, OTHER_REPO)
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
