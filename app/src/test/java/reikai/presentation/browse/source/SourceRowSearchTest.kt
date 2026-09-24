package reikai.presentation.browse.source

import io.kotest.matchers.shouldBe
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.source.SourceKey

/**
 * What a Sources row is called and what the search box finds it by, pinned once over both content
 * types: the rules live on the shared row, so neither type can word or match its rows its own way.
 */
class SourceRowSearchTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("keys")
    fun `a source named like its extension is titled by its name alone`(key: SourceKey) {
        row(key, name = "Asura", extensionName = "Asura").title shouldBe "Asura"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keys")
    fun `a source named unlike its extension carries the extension name too`(key: SourceKey) {
        row(key, name = "Asura", extensionName = "Scans Pack").title shouldBe "Asura (Scans Pack)"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keys")
    fun `the search finds a source by its extension's name`(key: SourceKey) {
        matchesSourceQuery(row(key, name = "Asura", extensionName = "Scans Pack"), "pack") shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keys")
    fun `the search finds a source by its exact id`(key: SourceKey) {
        matchesSourceQuery(row(key, name = "Asura"), idOf(key)) shouldBe true
    }

    @Test
    fun `a partial manga source id does not match`() {
        matchesSourceQuery(row(SourceKey.Manga(1234), name = "Asura"), "123") shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keys")
    fun `a query naming nothing about the source leaves it out`(key: SourceKey) {
        matchesSourceQuery(row(key, name = "Asura", extensionName = "Scans Pack"), "bato") shouldBe false
    }

    private fun idOf(key: SourceKey) = when (key) {
        is SourceKey.Manga -> key.id.toString()
        is SourceKey.Novel -> key.id
    }

    private fun row(key: SourceKey, name: String, extensionName: String = name) = BrowseSourceRow(
        key = key,
        name = name,
        lang = "en",
        isPinned = false,
        isUsedLast = false,
        supportsLatest = false,
        extensionName = extensionName,
        contentWarning = ContentWarning.SAFE,
        source = Unit,
    )

    companion object {
        @JvmStatic
        fun keys() = listOf(SourceKey.Manga(98765), SourceKey.Novel("novelfire"))
    }
}
