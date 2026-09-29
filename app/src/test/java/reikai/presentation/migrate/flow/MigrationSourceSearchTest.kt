package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.novel.source.NovelExtensionFormat

/**
 * The strip model both search routes publish: the single-entry search screen and the batch list's
 * override picker seed a loading strip per source and land each source's answer on its own strip.
 */
class MigrationSourceSearchTest {

    private fun source(key: String) =
        MigrationSourceUi(key, key.uppercase(), "en", MigrationSourceIcon.NovelUrl(null), NovelExtensionFormat.APK)

    @Test
    fun `a source's strip opens loading, carrying the source's heading`() {
        source("a").loadingStrip() shouldBe SourceStrip("a", "A", "en", NovelExtensionFormat.APK, StripResult.Loading)
    }

    @Test
    fun `a landed result fills only its own source's strip`() {
        val strips = listOf(source("a").loadingStrip(), source("b").loadingStrip())

        strips.withResult("b", StripResult.Failed("down")).map { it.result } shouldBe
            listOf(StripResult.Loading, StripResult.Failed("down"))
    }
}
