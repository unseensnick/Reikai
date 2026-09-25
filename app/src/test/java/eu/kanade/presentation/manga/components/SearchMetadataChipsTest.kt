package eu.kanade.presentation.manga.components

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SearchMetadataChipsTest {

    private fun searches(tagQuery: ((String, String) -> String)?) =
        SearchMetadataChips(null, 1L, listOf("tag: big breasts"), tagQuery)!!.tags.values.flatten().map { it.search }

    @Test
    fun `a chip from a source with no tag grammar searches the bare name`() {
        searches(tagQuery = null) shouldBe listOf("big breasts")
    }

    @Test
    fun `a chip from a metadata source searches in that source's grammar`() {
        searches { namespace, tag -> "$namespace=$tag" } shouldBe listOf("tag=big breasts")
    }
}
