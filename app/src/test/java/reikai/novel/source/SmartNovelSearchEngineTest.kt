package reikai.novel.source

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.novel.host.NovelItem

/**
 * The engine that stops a novel migration suggesting an unrelated title. Its threshold and the
 * single-candidate carve-out are inherited from Mihon's base engine, so these pin the behavior the
 * migration adapter depends on rather than the base engine's internals.
 */
class SmartNovelSearchEngineTest {

    private fun item(name: String) = NovelItem(name = name, path = "/${name.hashCode()}", cover = null)

    private suspend fun bestMatch(
        title: String,
        results: List<String>,
        extraQuery: String? = null,
    ): String? = SmartNovelSearchEngine(extraQuery)
        .bestMatch(title) { results.map(::item) }
        ?.name

    @Test
    fun `the closest title wins, not the first result`() = runTest {
        val match = bestMatch(
            title = "Reverend Insanity",
            results = listOf("Unrelated Cultivation Story", "Reverend Insanity", "Another Thing"),
        )

        match shouldBe "Reverend Insanity"
    }

    @Test
    fun `nothing similar enough means no suggestion at all`() = runTest {
        val match = bestMatch(
            title = "Reverend Insanity",
            results = listOf("Pie's Shizun-Wifing System", "Nemesis: Death Star Companion"),
        )

        match shouldBe null
    }

    @Test
    fun `an empty result list is not a match`() = runTest {
        bestMatch(title = "Reverend Insanity", results = emptyList()) shouldBe null
    }

    @Test
    fun `a lone result is taken unscored, the carve-out inherited from the base engine`() = runTest {
        // Documented in SmartNovelSearchEngine's KDoc, and the reason suggest() must pass the raw
        // hit list: pre-filtering can manufacture this case out of a list of near-duplicates.
        val match = bestMatch(title = "Reverend Insanity", results = listOf("Completely Different Book"))

        match shouldBe "Completely Different Book"
    }

    @Test
    fun `the extra query shapes the search without being scored against the title`() = runTest {
        var searched: String? = null
        val match = SmartNovelSearchEngine("english").bestMatch("Reverend Insanity") { query ->
            searched = query
            listOf(item("Reverend Insanity"), item("Something Else Entirely"))
        }

        searched shouldBe "Reverend Insanity english"
        match?.name shouldBe "Reverend Insanity"
    }
}
