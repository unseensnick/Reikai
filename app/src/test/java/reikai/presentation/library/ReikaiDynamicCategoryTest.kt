package reikai.presentation.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category

/**
 * A synthetic dynamic-grouping category packs its metadata into [Category.name] (a real DB category
 * can't carry sourceId/langId). These cover the encode -> decode contract the grouping relies on:
 * names are built exactly as LibraryDynamicGrouping builds them ("display SPLITTER id" for sources,
 * "code SPLITTER display" for languages).
 */
class ReikaiDynamicCategoryTest {

    private fun dynamic(name: String) = Category(id = -2, name = name, order = 0, flags = 0)

    private fun sourceName(display: String, sourceId: Long) =
        "$display${ReikaiDynamicCategory.SOURCE_SPLITTER}$sourceId"

    private fun langName(code: String, display: String) =
        "$code${ReikaiDynamicCategory.LANG_SPLITTER}$display"

    @Test
    fun `a negative id marks a synthetic category`() {
        ReikaiDynamicCategory.isDynamic(dynamic("anything")) shouldBe true
    }

    @Test
    fun `a non-negative id is a real category`() {
        ReikaiDynamicCategory.isDynamic(Category(id = 3, name = "Reading", order = 0, flags = 0)) shouldBe false
    }

    @Test
    fun `source group decodes its display name`() {
        ReikaiDynamicCategory.displayName(dynamic(sourceName("MangaDex", 5))) shouldBe "MangaDex"
    }

    @Test
    fun `source group decodes its source id`() {
        ReikaiDynamicCategory.sourceId(dynamic(sourceName("MangaDex", 5))) shouldBe 5L
    }

    @Test
    fun `source group has no language id`() {
        ReikaiDynamicCategory.langId(dynamic(sourceName("MangaDex", 5))) shouldBe null
    }

    @Test
    fun `language group decodes its display name`() {
        ReikaiDynamicCategory.displayName(dynamic(langName("en", "English"))) shouldBe "English"
    }

    @Test
    fun `language group decodes its language code`() {
        ReikaiDynamicCategory.langId(dynamic(langName("en", "English"))) shouldBe "en"
    }

    @Test
    fun `language group has no source id`() {
        ReikaiDynamicCategory.sourceId(dynamic(langName("en", "English"))) shouldBe null
    }

    @Test
    fun `a plain name decodes to itself`() {
        ReikaiDynamicCategory.displayName(dynamic("Favorites")) shouldBe "Favorites"
    }

    @Test
    fun `the encoded name is the stable collapse key`() {
        val name = sourceName("MangaDex", 5)
        ReikaiDynamicCategory.headerKey(dynamic(name)) shouldBe name
    }
}
