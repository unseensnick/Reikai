package reikai.presentation.novel.browse

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/** The value shapes an LNReader plugin reads at `options.filters.X.value` for its option lists. */
class LnFilterValuesTest {

    @Test
    fun `an include-exclude value is written with both lists, an empty one too`() {
        IncludeExclude(include = setOf("magic"), exclude = emptySet()).toJson().toString() shouldBe
            """{"include":["magic"],"exclude":[]}"""
    }

    @Test
    fun `an include-exclude value missing a list reads it as empty`() {
        IncludeExclude.of(Json.parseToJsonElement("""{"include":["magic"]}""")) shouldBe
            IncludeExclude(include = setOf("magic"), exclude = emptySet())
    }

    @Test
    fun `a checkbox value is the checked options' string array`() {
        checkboxValue(listOf("harem", "magic")).toString() shouldBe """["harem","magic"]"""
    }

    @Test
    fun `a checkbox value reads back as its checked options`() {
        checkedValues(Json.parseToJsonElement("""["harem","magic"]""")) shouldBe setOf("harem", "magic")
    }

    @Test
    fun `a value of another shape reads as nothing checked`() {
        checkedValues(JsonPrimitive("harem")) shouldBe emptySet()
    }
}
